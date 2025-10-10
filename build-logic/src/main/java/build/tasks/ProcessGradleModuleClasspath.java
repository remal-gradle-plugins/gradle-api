package build.tasks;

import static build.utils.GradleModuleClasspathUtils.getGradleClasspathModules;
import static build.utils.ZipUtils.getZipFileEntryNames;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.file.Files.isRegularFile;

import build.dto.GradleDependencies;
import build.dto.GradleDependencyInfo;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.gradle.api.tasks.CacheableTask;

/**
 * Analyzes Gradle JAR files and enriches the dependency graph with module-level classpath links.
 *
 * <p>Reads {@link GradleDependencies} and inspects Gradle library JARs located in {@link #getGradleFilesDirectory()}.
 * Detects relationships between Gradle modules by parsing each JAR’s classpath metadata
 * and cross-referencing contained class entries.
 *
 * <p>Processing logic:
 * <ul>
 *   <li>Scans Gradle JARs whose names start with {@code gradle-}
 *   <li>Extracts entries such as {@code .class} files
 *   <li>Reads embedded Gradle module classpath metadata
 *   <li>Identifies referenced Gradle module JARs and links them as sub-dependencies in the dependency graph
 *   <li>Skips known Gradle runtime libraries like {@code groovy-*}, {@code kotlin-*},
 *   {@code native-platform-*}, {@code file-events-*}, and {@code jansi-*}
 *   <li>Registers any new module dependencies not yet present in {@link GradleDependencies}
 * </ul>
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@link #getGradleDependenciesFile()} – file with {@link GradleDependencies} from the previous stage
 *   <li>{@link #getGradleFilesDirectory()} – directory with extracted Gradle JARs and metadata
 * </ul>
 *
 * <p>Outputs:
 * <ul>
 *   <li>{@link #getGradleDependenciesJsonFile()} – updated {@link GradleDependencies} file
 * </ul>
 */
@CacheableTask
public abstract class ProcessGradleModuleClasspath extends AbstractMappingDependenciesInfoTask {

    private static final Pattern LIB_FILE_PATTERN = Pattern.compile("\\.(so|dll|[^.]*lib)$");

    @Override
    protected GradleDependencies mapGradleDependencies(GradleDependencies gradleDependencies) {
        var gradleFilesDir = getGradleFilesDirectory().getAsFile().get().toPath();

        for (var depInfo : List.copyOf(gradleDependencies.getDependencies().values())) {
            var path = depInfo.getPath();
            if (path == null) {
                continue;
            }

            var file = getProjectRelativeFile(path);
            var isGradleFile = file.getName().startsWith("gradle-");
            if (!isGradleFile) {
                continue;
            }

            var essentialEntryNames = getZipFileEntryNames(file).stream()
                .filter(it ->
                    (it.endsWith(".class") && !it.equals("module-info.class") && !it.endsWith("/module-info.class"))
                        || LIB_FILE_PATTERN.matcher(it).find()
                )
                .collect(toImmutableSet());

            var modules = getGradleClasspathModules(file);
            var moduleDepPaths = modules.values().stream()
                .flatMap(info -> info.scopePaths().values().stream())
                .flatMap(Collection::stream)
                .filter(it -> it.endsWith(".jar"))
                .collect(toImmutableSet());
            for (var moduleDepPath : moduleDepPaths) {
                var moduleDepPathPrefix = '/' + moduleDepPath;
                if (moduleDepPathPrefix.startsWith("/gradle-")
                    || moduleDepPathPrefix.startsWith("/groovy-")
                    || moduleDepPathPrefix.startsWith("/kotlin-")
                    || moduleDepPathPrefix.startsWith("/native-platform-")
                    || moduleDepPathPrefix.startsWith("/file-events-")
                    || moduleDepPathPrefix.startsWith("/jansi-")
                ) {
                    continue;
                }

                var baseDir = file.toPath().getParent();
                if (file.getParentFile().toPath().equals(gradleFilesDir)) {
                    baseDir = baseDir.resolve("lib");
                }
                var moduleDepFile = baseDir.resolve(moduleDepPath);
                if (!isRegularFile(moduleDepFile)) {
                    continue;
                }

                var moduleFileEntryNames = getZipFileEntryNames(moduleDepFile.toFile());
                var moduleEntriesIncludedIntoFile = moduleFileEntryNames.stream()
                    .filter(essentialEntryNames::contains)
                    .toList();
                if (moduleEntriesIncludedIntoFile.isEmpty()) {
                    continue;
                }

                var moduleDepId = gradleDependencies.getDependencyIdByPathOrName(moduleDepFile);
                depInfo.getDependencies().add(moduleDepId);

                if (!gradleDependencies.getDependencies().containsKey(moduleDepId)) {
                    var moduleDepInfo = new GradleDependencyInfo();
                    moduleDepInfo.setPath(getProjectFileRelativePath(moduleDepFile));

                    gradleDependencies.getDependencies().put(moduleDepId, moduleDepInfo);
                }
            }
        }

        return gradleDependencies;
    }

}
