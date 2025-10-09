package build;

import static build.Constants.FALLBACK_JAVA_VERSION;
import static build.Constants.GRADLE_API_PUBLISH_GROUP;
import static java.nio.charset.StandardCharsets.UTF_8;

import build.tasks.AbstractGradleFilesConsumerTask;
import build.tasks.AbstractProducingDependenciesInfoTask;
import build.tasks.CompleteDependencies;
import build.tasks.CreateSimpleGradleDependencies;
import build.tasks.ExtractGradleFiles;
import build.tasks.ProcessGradleModuleClasspath;
import build.tasks.ProcessModuleRegistry;
import build.tasks.PublishArtifacts;
import build.tasks.PublishArtifactsToLocalBuildRepository;
import build.tasks.VerifyPublishedArtifactsToLocalBuildRepository;
import build.utils.DependenciesInjectable;
import build.utils.Utils;
import build.utils.WithGradleVersion;
import build.utils.WithLocalBuildRepository;
import build.utils.WithPublishLicense;
import build.utils.WithPublishRepository;
import java.util.ArrayList;
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
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

public abstract class BuildLogicPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        var extension = project.getExtensions().create("buildLogic", BuildLogicExtension.class);
        var gradleVersion = extension.getGradleVersion();

        getTasks().configureEach(task -> {
            if (task instanceof WithGradleVersion typed) {
                typed.getGradleVersion().convention(gradleVersion);
            }
            if (task instanceof WithPublishLicense typed) {
                typed.getLicense().getName().convention(extension.getLicense().getName());
                typed.getLicense().getUrl().convention(extension.getLicense().getUrl());
            }
            if (task instanceof WithLocalBuildRepository typed) {
                typed.getLocalBuildRepository().convention(extension.getLocalBuildRepository());
            }
            if (task instanceof WithPublishRepository typed) {
                typed.getRepository().getUrl().convention(extension.getRepository().getUrl());
                typed.getRepository().getUsername().convention(extension.getRepository().getUsername());
                typed.getRepository().getPassword().convention(extension.getRepository().getPassword());
            }
        });


        project.getPluginManager().apply("java-library");
        applyBasicJavaSettings(project);


        getTasks().withType(Test.class).configureEach(task -> {
            task.getJavaLauncher().set(getJavaToolchainService().launcherFor(spec -> {
                spec.getLanguageVersion().set(gradleVersion.map(Utils::getGradleJvmVersion));
            }));
        });


        project.afterEvaluate(_ -> {
            extension.getLocalBuildRepository().finalizeValueOnRead();

            getRepositories().exclusiveContent(exclusive -> {
                exclusive.forRepositories(
                    getRepositories().maven(maven -> {
                        maven.setName("localBuildRepository");
                        maven.setUrl(extension.getLocalBuildRepository().getAsFile().get().toURI());
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
            }
        );

        var verifyPublishedArtifactsToLocalBuildRepository = getTasks().register(
            "verifyPublishedArtifactsToLocalBuildRepository",
            VerifyPublishedArtifactsToLocalBuildRepository.class,
            task -> {
                task.getLocalBuildRepository().convention(
                    publishArtifactsToLocalBuildRepository
                        .flatMap(PublishArtifactsToLocalBuildRepository::getLocalBuildRepository)
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


        var publishArtifacts = getTasks().register(
            "publishArtifacts",
            PublishArtifacts.class,
            task -> {
                task.dependsOn(getTasks().withType(VerifyPublishedArtifactsToLocalBuildRepository.class));
                task.dependsOn(getTasks().withType(AbstractTestTask.class));

                task.getLocalBuildRepository().convention(
                    publishArtifactsToLocalBuildRepository
                        .flatMap(PublishArtifactsToLocalBuildRepository::getLocalBuildRepository)
                );
            }
        );
    }

    private void applyBasicJavaSettings(Project project) {
        project.getExtensions().getByType(JavaPluginExtension.class).getToolchain().getLanguageVersion().set(
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
            task.getJvmArgumentProviders().add(() -> {
                var args = new ArrayList<String>();
                var javaVersion = task.getJavaLauncher()
                    .map(JavaLauncher::getMetadata)
                    .map(JavaInstallationMetadata::getLanguageVersion)
                    .map(JavaLanguageVersion::asInt)
                    .get();
                if (javaVersion >= 9) {
                    // see https://github.com/gradle/gradle/issues/18647
                    args.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
                }
                if (javaVersion >= 24) {
                    // see https://github.com/gradle/gradle/issues/31625
                    args.add("--enable-native-access=ALL-UNNAMED");
                }
                return args;
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
