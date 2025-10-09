package build.tasks;

import static build.utils.Utils.createCleanDirectory;
import static build.utils.Utils.createGradleContent;
import static java.lang.String.format;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.writeString;

import build.utils.Utils;
import build.utils.WithGradleVersion;
import java.net.URI;
import java.util.Map;
import java.util.Properties;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.tooling.CancellationToken;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.internal.consumer.CancellationTokenInternal;
import org.gradle.util.GradleVersion;

@CacheableTask
public abstract class ExtractGradleFiles
    extends AbstractBuildLogicTask
    implements WithGradleVersion {

    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    {
        getJavaLauncher().set(getJavaToolchainService().launcherFor(spec -> {
            spec.getLanguageVersion().set(getGradleVersion().map(Utils::getGradleJvmVersion));
        }));
    }


    @OutputDirectory
    public abstract DirectoryProperty getGradleFilesDirectory();

    {
        getGradleFilesDirectory().convention(getLayout().getBuildDirectory().dir(getName()));
    }

    @OutputFile
    public abstract RegularFileProperty getGradleRawDependenciesJsonFile();

    {
        getGradleRawDependenciesJsonFile().convention(getGradleFilesDirectory().file("info.json"));
    }


    {
        onlyIf(__ -> {
            getGradleVersion().finalizeValueOnRead();
            getJavaLauncher().finalizeValueOnRead();
            getGradleFilesDirectory().finalizeValueOnRead();
            getGradleRawDependenciesJsonFile().finalizeValueOnRead();
            return true;
        });
    }


    @TaskAction
    public void execute() throws Exception {
        var outputFile = getGradleRawDependenciesJsonFile().getAsFile().get().toPath();
        deleteIfExists(outputFile);
        createDirectories(outputFile.getParent());

        var gradleFilesDirectory = createCleanDirectory(getGradleFilesDirectory().getAsFile().get().toPath());

        var tempDir = createCleanDirectory(getTemporaryDir().toPath());
        var tempProjectDir = createCleanDirectory(tempDir.resolve("project"));

        var gradleVersionString = getGradleVersion().get();
        var baseGradleVersion = GradleVersion.version(gradleVersionString).getBaseVersion();

        writeString(tempProjectDir.resolve("settings.gradle"), createGradleContent(
            """
                rootProject.name = '#ROOT_PROJECT_NAME#'
                """,
            Map.of(
                "ROOT_PROJECT_NAME", getGradleVersion()
            )
        ));

        writeString(tempProjectDir.resolve("build.gradle"), createGradleContent(
            """
                import groovy.json.JsonOutput
                import java.nio.charset.StandardCharsets
                import java.nio.file.Files
                import java.nio.file.Paths
                import java.nio.file.StandardCopyOption
                import org.gradle.util.GradleVersion

                def currentGradleVersion = GradleVersion.current().baseVersion
                def buildProjectDir = file('#BUILD_PROJECT_DIR#')

                def gradleHomeDir = gradle.gradleHomeDir?.canonicalFile
                assert gradleHomeDir != null

                def gradleLibDir = new File(gradleHomeDir, 'lib')
                assert gradleLibDir.isDirectory()

                def sourcesParentDir = new File(gradleHomeDir, 'src')
                assert sourcesParentDir.isDirectory()

                def sourcesDirs = sourcesParentDir.listFiles().findAll{ file -> file.isDirectory() }
                def sourcesArchiveFile = file('#GRADLE_FILES_DIR#/sources.zip')

                tasks.#TASK_CREATION_METHOD#('archiveSources', Zip) {
                    sourcesDirs.forEach { from(it) }

                    if (currentGradleVersion >= GradleVersion.version('5.1')) {
                        archiveFileName = sourcesArchiveFile.name
                        destinationDirectory = sourcesArchiveFile.parentFile
                    } else {
                        archiveName = sourcesArchiveFile.name
                        destinationDir = sourcesArchiveFile.parentFile
                    }

                    if (currentGradleVersion >= GradleVersion.version('2.14')) {
                        metadataCharset = 'UTF-8'
                    }
                    if (currentGradleVersion >= GradleVersion.version('3.4')) {
                        preserveFileTimestamps  = true
                        reproducibleFileOrder = true
                    }
                    includeEmptyDirs = false
                    duplicatesStrategy = 'EXCLUDE'
                }


                tasks.#TASK_CREATION_METHOD#('copyLibs', Copy) {
                    from(gradleLibDir)
                    into('#GRADLE_FILES_DIR#/lib')
                    include('**/*.jar')
                }


                Map<String, Dependency> dependencyMethods = [
                    'localGroovy': project.dependencies.localGroovy(),
                    'gradleApi': project.dependencies.gradleApi(),
                    'gradleTestKit': project.dependencies.gradleTestKit(),
                ]
                if (currentGradleVersion >= GradleVersion.version('5.0')) {
                    dependencyMethods['gradleKotlinDsl'] = project.dependencies.create(project.files(
                        Class.forName('org.gradle.kotlin.dsl.provider.KotlinScriptClassPathProviderKt').gradleKotlinDslOf(project)
                    ))
                }

                Map<String, FileCollection> allDependencyFiles = [:]
                dependencyMethods.forEach { dependencyMethod, dependency ->
                    def dependencyFiles = project.configurations.detachedConfiguration(dependency).files.collect { it.canonicalFile }
                    allDependencyFiles[dependencyMethod] = project.files(dependencyFiles)
                }

                tasks.#TASK_CREATION_METHOD#('extract') {
                    dependsOn('archiveSources')
                    dependsOn('copyLibs')

                    doLast {
                        def result = [
                            gradleVersion: GradleVersion.current().version,
                            sourcesArchiveFile: buildProjectDir.toPath().relativize(sourcesArchiveFile.toPath()).toString().replace("\\\\", "/"),
                        ]

                        def resultDependencies = result['dependencies'] = [:]
                        allDependencyFiles.forEach { dependencyMethod, dependencyFiles ->
                            def destFiles = resultDependencies[dependencyMethod] = []
                            dependencyFiles.forEach { file ->
                                if (file.toPath().startsWith(gradleLibDir.toPath())) {
                                    def relativePath = gradleLibDir.toPath().relativize(file.toPath()).toString().replace("\\\\", "/")
                                    def destFile = new File('#GRADLE_FILES_DIR#/lib', relativePath)
                                    destFiles.add(buildProjectDir.toPath().relativize(destFile.toPath()).toString().replace("\\\\", "/"))
                                } else {
                                    def destFile = new File('#GRADLE_FILES_DIR#', file.name)
                                    destFile.parentFile.mkdirs()
                                    Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                    destFiles.add(buildProjectDir.toPath().relativize(destFile.toPath()).toString().replace("\\\\", "/"))
                                }
                            }
                        }

                        def resultJson = JsonOutput.prettyPrint(JsonOutput.toJson(result))
                        def outputFile = Paths.get('#OUTPUT_FILE#')
                        Files.createDirectories(outputFile.parent)
                        Files.write(outputFile, resultJson.getBytes(StandardCharsets.UTF_8))
                    }
                }
                """,
            Map.of(
                "TASK_CREATION_METHOD", baseGradleVersion.compareTo(GradleVersion.version("9.0")) < 0
                    ? "create"
                    : "register",
                "OUTPUT_FILE", outputFile,
                "GRADLE_FILES_DIR", gradleFilesDirectory,
                "BUILD_PROJECT_DIR", getLayout().getProjectDirectory()
            )
        ));

        var gradleProperties = new Properties();
        gradleProperties.setProperty("org.gradle.logging.stacktrace", "all");
        gradleProperties.setProperty("org.gradle.configuration-cache", "false");
        gradleProperties.setProperty("org.gradle.caching", "false");
        gradleProperties.setProperty("org.gradle.parallel", "false");
        gradleProperties.setProperty("org.gradle.daemon.idletimeout", "2500");
        gradleProperties.setProperty("org.gradle.ignoreInitScripts", "true");
        try (var out = newOutputStream(tempProjectDir.resolve("gradle.properties"))) {
            gradleProperties.store(out, null);
        }

        try (
            var connection = GradleConnector.newConnector()
                .forProjectDirectory(tempProjectDir.toFile())
                .useDistribution(URI.create(format(
                    "https://services.gradle.org/distributions/gradle-%s-all.zip",
                    gradleVersionString
                )))
                .connect()
        ) {
            connection.newBuild()
                .setJavaHome(getJavaLauncher().get().getMetadata().getInstallationPath().getAsFile())
                .setStandardOutput(System.out)
                .setStandardError(System.err)
                .addJvmArguments(
                    gradleProperties.entrySet().stream()
                        .map(entry -> format("-D%s=%s", entry.getKey(), entry.getValue()))
                        .toArray(String[]::new)
                )
                .withCancellationToken(new GradleConnectorCancellationToken())
                .forTasks("extract")
                .run();
        }
    }

    private class GradleConnectorCancellationToken implements CancellationToken, CancellationTokenInternal {

        @Override
        public BuildCancellationToken getToken() {
            return getBuildCancellationToken();
        }

        @Override
        public boolean isCancellationRequested() {
            return getToken().isCancellationRequested();
        }

    }

}
