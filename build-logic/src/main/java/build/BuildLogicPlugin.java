package build;

import static build.Constants.GRADLE_API_PUBLISH_GROUP;

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
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.testing.AbstractTestTask;
import org.gradle.api.tasks.testing.Test;

public class BuildLogicPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        var tasks = project.getTasks();
        var repositories = project.getRepositories();
        var dependencies = project.getDependencies();
        var configurations = project.getConfigurations();
        var objects = project.getObjects();


        project.getPluginManager().apply("java-library");


        var extension = project.getExtensions().create("buildLogic", BuildLogicExtension.class);
        var gradleVersion = extension.getGradleVersion();


        repositories.mavenCentral();

        project.afterEvaluate(_ -> {
            extension.getLocalMavenRepository().finalizeValueOnRead();

            repositories.exclusiveContent(exclusive -> {
                exclusive.forRepositories(
                    repositories.maven(maven -> {
                        maven.setName("localBuildRepository");
                        maven.setUrl(extension.getLocalMavenRepository().getAsFile().get().toURI());
                    })
                );
                exclusive.filter(filter -> {
                    filter.includeGroup(GRADLE_API_PUBLISH_GROUP);
                });
            });
        });


        var extractGradleFiles = tasks.register(
            "extractGradleFiles",
            ExtractGradleFiles.class,
            task -> {
                task.getGradleVersion().convention(gradleVersion);
            }
        );

        tasks.withType(AbstractGradleFilesConsumerTask.class).configureEach(task -> {
            task.getGradleFilesDirectory().convention(
                extractGradleFiles.flatMap(ExtractGradleFiles::getGradleFilesDirectory)
            );
        });


        var simpleGradleDependencies = tasks.register(
            "simpleGradleDependencies",
            CreateSimpleGradleDependencies.class,
            task -> {
                task.getRawGradleDependenciesFile().convention(
                    extractGradleFiles.flatMap(ExtractGradleFiles::getGradleRawDependenciesJsonFile)
                );
            }
        );

        var processGradleModuleClasspath = tasks.register(
            "processGradleModuleClasspath",
            ProcessGradleModuleClasspath.class,
            task -> {
                task.getGradleDependenciesFile().convention(
                    simpleGradleDependencies.flatMap(AbstractProducingDependenciesInfoTask::getGradleDependenciesJsonFile)
                );
            }
        );

        var processModuleRegistry = tasks.register(
            "processModuleRegistry",
            ProcessModuleRegistry.class,
            task -> {
                task.getGradleDependenciesFile().convention(
                    processGradleModuleClasspath.flatMap(AbstractProducingDependenciesInfoTask::getGradleDependenciesJsonFile)
                );
            }
        );

        var completeDependencies = tasks.register(
            "completeDependencies",
            CompleteDependencies.class,
            task -> {
                task.getGradleDependenciesFile().convention(
                    processModuleRegistry.flatMap(AbstractProducingDependenciesInfoTask::getGradleDependenciesJsonFile)
                );
            }
        );


        var publishArtifactsToLocalBuildRepository = tasks.register(
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

        var verifyPublishedArtifactsToLocalBuildRepository = tasks.register(
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

        tasks.withType(AbstractTestTask.class).configureEach(task -> {
            task.dependsOn(verifyPublishedArtifactsToLocalBuildRepository);
        });

        tasks.withType(Test.class).configureEach(task -> {
            task.notCompatibleWithConfigurationCache("Resolves configurations at execution");
            var deps = objects.newInstance(DependenciesInjectable.class);
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


        tasks.register("last-task", task -> {
            task.setGroup("gradle-api");
            task.dependsOn(verifyPublishedArtifactsToLocalBuildRepository);
        });
    }

}
