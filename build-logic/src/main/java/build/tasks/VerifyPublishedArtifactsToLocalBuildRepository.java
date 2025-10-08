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
import java.io.File;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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

@CacheableTask
public abstract class VerifyPublishedArtifactsToLocalBuildRepository
    extends AbstractBuildLogicTask
    implements VerificationTask {

    {
        notCompatibleWithConfigurationCache("Resolves configurations at execution");
    }


    @InputDirectory
    @PathSensitive(RELATIVE)
    public abstract DirectoryProperty getGradlePublishedDependenciesDir();

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
            getGradlePublishedDependenciesDir().finalizeValueOnRead();
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
        return getGradlePublishedDependenciesDir().file(path).get().getAsFile();
    }

}
