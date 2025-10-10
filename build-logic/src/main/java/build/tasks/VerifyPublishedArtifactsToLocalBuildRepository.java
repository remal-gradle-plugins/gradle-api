package build.tasks;

import static build.Constants.GRADLE_API_BOM_NAME;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.writeString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.api.tasks.PathSensitivity.RELATIVE;

import build.dto.GradlePublishedDependencies;
import build.dto.GradlePublishedDependencyInfo;
import build.utils.Json;
import build.utils.WithLocalBuildRepository;
import java.io.File;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.gradle.api.BuildCancelledException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.VerificationTask;
import org.jspecify.annotations.Nullable;

/**
 * Verifies that artifacts published to the local Gradle Maven-style build repository can be successfully resolved.
 *
 * <p>Consumes the JSON metadata produced by {@link PublishArtifactsToLocalBuildRepository}
 * and checks that each declared artifact (POM, JAR, sources JAR) exists and is correctly resolvable
 * using Gradle’s dependency resolution mechanism.
 *
 * <p>Validation logic:
 * <ul>
 *   <li>Loads {@link GradlePublishedDependencies} describing all published artifacts
 *   <li>For each dependency:
 *     <ul>
 *       <li>Verifies that the POM file exists and can be resolved
 *       <li>Verifies that the main artifact JAR exists and resolves successfully
 *           (except for the Gradle API BOM, which has no JAR)
 *       <li>Verifies that the sources JAR (if present) resolves successfully
 *     </ul>
 *   <li>Resolves each artifact using detached configurations to ensure correctness
 * </ul>
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@link #getLocalBuildRepository()} – local Gradle Maven-style build repository
 *   <li>{@link #getGradlePublishedDependenciesJsonFile()} – {@link GradlePublishedDependencies} file
 *   describing published dependencies
 *   <li>{@link #getIgnoreFailures()} – controls whether verification failures abort the build
 * </ul>
 */
@CacheableTask
public abstract class VerifyPublishedArtifactsToLocalBuildRepository
    extends AbstractBuildLogicTask
    implements WithLocalBuildRepository, VerificationTask {

    {
        notCompatibleWithConfigurationCache("Resolves configurations at execution");
    }


    @InputDirectory
    @PathSensitive(RELATIVE)
    @Override
    public abstract DirectoryProperty getLocalBuildRepository();

    @InputFile
    @PathSensitive(RELATIVE)
    public abstract RegularFileProperty getGradlePublishedDependenciesJsonFile();


    @OutputFile
    public abstract RegularFileProperty getStatusFile();

    {
        getStatusFile().convention(getLayout().getBuildDirectory().file(getName() + "/status.txt"));
    }


    private boolean ignoreFailures = false;

    @Override
    public void setIgnoreFailures(boolean ignoreFailures) {
        this.ignoreFailures = ignoreFailures;
    }

    @Override
    @Input
    public boolean getIgnoreFailures() {
        return ignoreFailures;
    }


    {
        onlyIf(__ -> {
            getLocalBuildRepository().finalizeValueOnRead();
            getGradlePublishedDependenciesJsonFile().finalizeValueOnRead();
            getStatusFile().finalizeValueOnRead();
            return true;
        });
    }


    @TaskAction
    public void execute() throws Throwable {
        var outputFile = getStatusFile().getAsFile().get().toPath();
        deleteIfExists(outputFile);
        createDirectories(outputFile.getParent());

        var publishedDependencies = Json.JSON_READER.readValue(
            getGradlePublishedDependenciesJsonFile().get().getAsFile(),
            GradlePublishedDependencies.class
        );

        publishedDependencies.getDependencies().forEach((id, info) -> {
            assertThatResolvedFiles(
                info,
                id + "@pom",
                GradlePublishedDependencyInfo::getPomFilePath,
                true,
                true
            );
            assertThatResolvedFiles(
                info,
                id.toString(),
                GradlePublishedDependencyInfo::getJarFilePath,
                false,
                !id.getName().equals(GRADLE_API_BOM_NAME)
            );
            assertThatResolvedFiles(
                info,
                id + ":sources",
                GradlePublishedDependencyInfo::getSourcesJarFilePath,
                false,
                false
            );
        });

        writeString(outputFile, "OK");
    }

    private void assertThatResolvedFiles(
        GradlePublishedDependencyInfo publishedDepInfo,
        String notation,
        Function<GradlePublishedDependencyInfo, @Nullable String> pathGetter,
        boolean checkExpectedFile,
        boolean mandatory
    ) {
        if (getBuildCancellationToken().isCancellationRequested()) {
            throw new BuildCancelledException();
        }

        try {
            var expectedFile = Optional.ofNullable(pathGetter.apply(publishedDepInfo))
                .map(this::getGradleFile)
                .orElse(null);
            if (checkExpectedFile) {
                assertThat(expectedFile).isNotNull();

            } else if (!mandatory && expectedFile == null) {
                return;
            }

            final Set<File> resolvedFiles;
            try {
                var dependency = getDependencies().create(notation);
                var configuration = getConfigurations().detachedConfiguration(dependency);
                resolvedFiles = configuration.getFiles();
            } catch (Throwable exception) {
                throw new AssertionError("Failed to resolve configuration: " + notation, exception);
            }
            assertThat(resolvedFiles).isNotEmpty();

            if (expectedFile != null) {
                assertThat(resolvedFiles).contains(expectedFile);
            }

        } catch (AssertionError exception) {
            if (ignoreFailures) {
                getLogger().error(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    protected final File getGradleFile(String path) {
        return getLocalBuildRepository().file(path).get().getAsFile();
    }

}
