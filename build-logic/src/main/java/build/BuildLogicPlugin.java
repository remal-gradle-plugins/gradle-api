package build;

import static build.Constants.FALLBACK_JAVA_VERSION;
import static build.Constants.GRADLE_API_PUBLISH_GROUP;
import static build.Constants.MIN_GRADLE_VERSION_TO_JAVA_VERSION;
import static java.nio.charset.StandardCharsets.UTF_8;

import build.tasks.AbstractGradleFilesConsumerTask;
import build.tasks.AbstractProducingDependenciesInfoTask;
import build.tasks.CompleteDependencies;
import build.tasks.CreateSimpleGradleDependencies;
import build.tasks.ExtractGradleFiles;
import build.tasks.ProcessGradleModuleClasspath;
import build.tasks.ProcessModuleRegistry;
import build.tasks.PublishArtifactsToLocalBuildRepository;
import build.tasks.VerifyPublishedArtifactsToLocalBuildRepository;
import build.utils.DependenciesInjectable;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.AbstractTestTask;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestStackTraceFilter;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.util.GradleVersion;

public abstract class BuildLogicPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        var extension = project.getExtensions().create("buildLogic", BuildLogicExtension.class);
        var gradleVersion = extension.getGradleVersion();
        var gradleJvmVersion = gradleVersion
            .map(GradleVersion::version)
            .map(GradleVersion::getBaseVersion)
            .map(version -> {
                for (var entry : MIN_GRADLE_VERSION_TO_JAVA_VERSION.entrySet()) {
                    if (version.compareTo(entry.getKey()) >= 0) {
                        return entry.getValue();
                    }
                }
                return FALLBACK_JAVA_VERSION;
            });


        project.getPluginManager().apply("java-library");

        var javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        javaExtension.getToolchain().getLanguageVersion().set(
            JavaLanguageVersion.of(25) // TODO: generate
        );

        getRepositories().mavenCentral();

        var allConstraints = getConfigurations().dependencyScope("allConstraints");
        getConfigurations()
            .matching(Configuration::isCanBeResolved)
            .configureEach(otherConf -> {
                otherConf.extendsFrom(allConstraints.get());
            });

        getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().getRelease().set(FALLBACK_JAVA_VERSION.asInt());
            task.getOptions().setEncoding(UTF_8.name());
            task.getOptions().setDeprecation(true);
            task.getOptions().getCompilerArgs().addAll(List.of(
                "-parameters",
                "-Werror",
                "-Xlint:all",
                "-Xlint:-rawtypes",
                "-Xlint:-serial",
                "-Xlint:-processing",
                "-Xlint:-this-escape",
                "-Xlint:-options"
            ));
        });

        getTasks().withType(Test.class).configureEach(task -> {
            task.getJavaLauncher().set(getJavaToolchainService().launcherFor(spec -> {
                spec.getLanguageVersion().set(gradleJvmVersion);
            }));

            // see https://github.com/gradle/gradle/issues/18647
            task.getJvmArgumentProviders().add(() -> {
                if (gradleJvmVersion.get().asInt() >= 9) {
                    return List.of(
                        "--add-opens=java.base/java.lang=ALL-UNNAMED"
                    );
                }
                return List.of();
            });

            // see https://github.com/gradle/gradle/issues/31625
            task.getJvmArgumentProviders().add(() -> {
                if (gradleJvmVersion.get().asInt() >= 24) {
                    return List.of(
                        "--enable-native-access=ALL-UNNAMED"
                    );
                }
                return List.of();
            });

            task.setEnableAssertions(true);

            task.testLogging(logging -> {
                logging.setShowExceptions(true);
                logging.setShowCauses(true);
                logging.setShowStackTraces(true);
                logging.setExceptionFormat(TestExceptionFormat.FULL);
                logging.stackTraceFilters(TestStackTraceFilter.GROOVY);
            });
        });


        project.afterEvaluate(_ -> {
            extension.getLocalMavenRepository().finalizeValueOnRead();

            getRepositories().exclusiveContent(exclusive -> {
                exclusive.forRepositories(
                    getRepositories().maven(maven -> {
                        maven.setName("localBuildRepository");
                        maven.setUrl(extension.getLocalMavenRepository().getAsFile().get().toURI());
                    })
                );
                exclusive.filter(filter -> {
                    filter.includeGroup(GRADLE_API_PUBLISH_GROUP);
                });
            });
        });


        var extractGradleFiles = getTasks().register(
            "extractGradleFiles",
            ExtractGradleFiles.class,
            task -> {
                task.getGradleVersion().convention(gradleVersion);
            }
        );

        getTasks().withType(AbstractGradleFilesConsumerTask.class).configureEach(task -> {
            task.getGradleFilesDirectory().convention(
                extractGradleFiles.flatMap(ExtractGradleFiles::getGradleFilesDirectory)
            );
        });


        var simpleGradleDependencies = getTasks().register(
            "simpleGradleDependencies",
            CreateSimpleGradleDependencies.class,
            task -> {
                task.getRawGradleDependenciesFile().convention(
                    extractGradleFiles.flatMap(ExtractGradleFiles::getGradleRawDependenciesJsonFile)
                );
            }
        );

        var processGradleModuleClasspath = getTasks().register(
            "processGradleModuleClasspath",
            ProcessGradleModuleClasspath.class,
            task -> {
                task.getGradleDependenciesFile().convention(
                    simpleGradleDependencies.flatMap(AbstractProducingDependenciesInfoTask::getGradleDependenciesJsonFile)
                );
            }
        );

        var processModuleRegistry = getTasks().register(
            "processModuleRegistry",
            ProcessModuleRegistry.class,
            task -> {
                task.getGradleDependenciesFile().convention(
                    processGradleModuleClasspath.flatMap(AbstractProducingDependenciesInfoTask::getGradleDependenciesJsonFile)
                );
            }
        );

        var completeDependencies = getTasks().register(
            "completeDependencies",
            CompleteDependencies.class,
            task -> {
                task.getGradleDependenciesFile().convention(
                    processModuleRegistry.flatMap(AbstractProducingDependenciesInfoTask::getGradleDependenciesJsonFile)
                );
            }
        );


        var publishArtifactsToLocalBuildRepository = getTasks().register(
            "publishArtifactsToLocalBuildRepository",
            PublishArtifactsToLocalBuildRepository.class,
            task -> {
                task.getGradleDependenciesFile().convention(
                    completeDependencies.flatMap(AbstractProducingDependenciesInfoTask::getGradleDependenciesJsonFile)
                );
                task.getOutputDirectory().convention(extension.getLocalMavenRepository());
                task.getLicense().getName().convention(extension.getLicense().getName());
                task.getLicense().getUrl().convention(extension.getLicense().getUrl());
            }
        );

        var verifyPublishedArtifactsToLocalBuildRepository = getTasks().register(
            "verifyPublishedArtifactsToLocalBuildRepository",
            VerifyPublishedArtifactsToLocalBuildRepository.class,
            task -> {
                task.getGradlePublishedDependenciesDir().convention(
                    publishArtifactsToLocalBuildRepository
                        .flatMap(PublishArtifactsToLocalBuildRepository::getOutputDirectory)
                );
                task.getGradlePublishedDependenciesJsonFile().convention(
                    publishArtifactsToLocalBuildRepository
                        .flatMap(PublishArtifactsToLocalBuildRepository::getGradlePublishedDependenciesJsonFile)
                );
            }
        );

        getTasks().withType(AbstractTestTask.class).configureEach(task -> {
            task.dependsOn(verifyPublishedArtifactsToLocalBuildRepository);
        });

        getTasks().withType(Test.class).configureEach(task -> {
            task.notCompatibleWithConfigurationCache("Resolves configurations at execution");
            var deps = getObjects().newInstance(DependenciesInjectable.class);
            task.onlyIf("Update classpath", currentTask -> {
                var test = (Test) currentTask;
                test.setClasspath(
                    deps.getConfigurations().detachedConfiguration(deps.getDependencies().create(
                        GRADLE_API_PUBLISH_GROUP + ":gradle-test-kit:" + gradleVersion.get()
                    )).plus(test.getClasspath())
                );
                return true;
            });
        });


        getTasks().register("last-task", task -> {
            task.setGroup("gradle-api");
            task.dependsOn(verifyPublishedArtifactsToLocalBuildRepository);
        });
    }


    @Inject
    protected abstract TaskContainer getTasks();

    @Inject
    protected abstract RepositoryHandler getRepositories();

    @Inject
    protected abstract ConfigurationContainer getConfigurations();

    @Inject
    protected abstract JavaToolchainService getJavaToolchainService();

    @Inject
    protected abstract ObjectFactory getObjects();

}
