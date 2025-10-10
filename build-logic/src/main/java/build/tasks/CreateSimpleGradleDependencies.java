package build.tasks;

import static org.gradle.api.tasks.PathSensitivity.RELATIVE;

import build.dto.GradleDependencies;
import build.dto.GradleDependencyInfo;
import build.dto.GradleRawDependencies;
import build.utils.Json;
import java.util.Collection;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;

/**
 * Converts raw Gradle dependency metadata into a structured dependency graph.
 *
 * <p>Reads the JSON file produced by {@link ExtractGradleFiles}, containing Gradle version,
 * sources archive location, and dependency file paths. Builds a {@link GradleDependencies}
 * model that links dependency methods to their corresponding files.
 *
 * <p>Transformation logic:
 * <ul>
 *   <li>Creates a root {@link GradleDependencyInfo} for each dependency method such as
 *   {@link DependencyHandler#gradleApi()}, {@link DependencyHandler#localGroovy()}
 *   {@link DependencyHandler#gradleTestKit()}, and {@code gradleKotlinDsl()}.
 *   <li>Assigns the main JAR path to each root dependency and adds related files as sub-dependencies.
 *   <li>Ensures every referenced file path has a corresponding {@link GradleDependencyInfo} entry.
 * </ul>
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@link #getRawGradleDependenciesFile()} – raw dependency metadata in JSON format
 *   <li>{@link #getGradleFilesDirectory()} – directory with extracted Gradle files
 * </ul>
 *
 * <p>Outputs:
 * <ul>
 *   <li>{@link #getGradleDependenciesJsonFile()} – file with {@link GradleDependencies}
 * </ul>
 */
@CacheableTask
public abstract class CreateSimpleGradleDependencies extends AbstractProducingDependenciesInfoTask {

    @InputFile
    @PathSensitive(RELATIVE)
    public abstract RegularFileProperty getRawGradleDependenciesFile();

    @Override
    protected GradleDependencies createGradleDependencies() throws Exception {
        var rawDeps = Json.JSON_READER.readValue(
            getRawGradleDependenciesFile().get().getAsFile(),
            GradleRawDependencies.class
        );


        var result = new GradleDependencies(
            rawDeps.getGradleVersion(),
            rawDeps.getSourcesArchiveFile()
        );

        rawDeps.getDependencies().forEach((methodName, paths) -> {
            var depId = result.getDependencyIdByMethodName(methodName);
            var depInfo = new GradleDependencyInfo();
            depInfo.setRoot(true);
            result.getDependencies().put(depId, depInfo);


            for (var path : paths) {
                var pathDepId = result.getDependencyIdByPathOrName(path);
                if (pathDepId.equals(depId)) {
                    if (depInfo.getPath() == null) {
                        depInfo.setPath(path);
                    } else {
                        throw new IllegalStateException("Multiple primary paths for " + methodName + ": " + paths);
                    }

                } else {
                    depInfo.getDependencies().add(pathDepId);
                }
            }
        });

        rawDeps.getDependencies().values().stream()
            .flatMap(Collection::stream)
            .forEach(path -> {
                var id = result.getDependencyIdByPathOrName(path);
                if (!result.getDependencies().containsKey(id)) {
                    var info = new GradleDependencyInfo();
                    info.setPath(path);
                    result.getDependencies().put(id, info);
                }
            });

        return result;
    }

}
