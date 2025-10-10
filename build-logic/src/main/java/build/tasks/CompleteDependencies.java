package build.tasks;

import static build.Constants.GRADLE_API_PUBLISH_GROUP;
import static build.utils.Utils.compareVersions;
import static build.utils.Utils.substringBefore;
import static build.utils.ZipUtils.getZipFileEntryNames;
import static java.lang.String.join;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toCollection;

import build.Constants;
import build.dto.GradleDependencies;
import build.dto.GradleDependencyId;
import build.dto.GradleDependencyInfo;
import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import lombok.SneakyThrows;
import org.gradle.api.tasks.CacheableTask;
import org.jspecify.annotations.Nullable;

/**
 * Completes Gradle dependency metadata by inferring missing group IDs, BOM associations,
 * and normalizing versions for known libraries.
 *
 * <p>Reads {@link GradleDependencies} and enriches it
 * with additional metadata derived from file inspection and heuristics.
 * The resulting model contains fully qualified dependency coordinates
 * (group, name, version) suitable for publication or comparison.
 *
 * <p>Processing logic:
 * <ul>
 *   <li>Reads {@code META-INF/maven/<group>/<name>/pom.properties} files embedded in dependency JARs
 *   to infer missing group IDs
 *   <li>Fixes version strings for certain special cases
 *   <li>Determines group IDs for known dependencies based on name patterns or known content,
 *       using predefined mappings such as {@code groovy-*}, {@code kotlin-*}, etc.
 *   <li>Assigns published {@link Constants#GRADLE_API_PUBLISH_GROUP} group to Gradle-related artifacts
 *       or synthetic dependencies
 *   <li>Identifies BOM artifacts (e.g. {@code groovy-bom}, {@code kotlin-bom}, etc.)
 *   <li>Detects and normalizes {@code -SNAPSHOT} versions, assigning them synthetic Gradle groups
 *   <li>Validates that all dependencies have resolved group IDs, throwing an error if any remain unset
 * </ul>
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@link #getGradleDependenciesFile()} – file with {@link GradleDependencies} from the previous stage
 *   <li>{@link #getGradleFilesDirectory()} – directory containing Gradle and third-party JARs
 * </ul>
 *
 * <p>Outputs:
 * <ul>
 *   <li>{@link #getGradleDependenciesJsonFile()} – updated {@link GradleDependencies} file
 * </ul>
 */
@CacheableTask
@SuppressWarnings("IfCanBeSwitch")
public abstract class CompleteDependencies extends AbstractMappingDependenciesInfoTask {

    private static final Map<String, String> DEP_NAME_TO_GROUP = ImmutableMap.<String, String>builder()
        .put("kotlin", "org.jetbrains.kotlin")
        .put("ant", "org.apache.ant")
        .put("jspecify", "org.jspecify")
        .put("jsr305", "com.google.code.findbugs")
        .put("javax.inject", "javax.inject")
        .put("xml-apis", "xml-apis")
        .put("asm", "org.ow2.asm")
        .put("jarjar", "com.googlecode.jarjar")
        .put("jna", "net.java.dev.jna")
        .put("objenesis", "org.objenesis")
        .put("ivy", "org.apache.ivy")
        .put("jcip-annotations", "net.jcip")
        .put("gson", "com.google.code.gson")
        .put("bcprov", "org.bouncycastle")
        .put("bcpg", "org.bouncycastle")
        .put("nekohtml", "net.sourceforge.nekohtml")
        .put("jcifs", "jcifs")
        .put("xercesImpl", "xerces")
        .put("junit", "junit")
        .put("hamcrest", "org.hamcrest")
        .put("rhino", "org.mozilla")
        .put("bndlib", "biz.aQute.bnd")
        .put("bsh", "org.beanshell")
        .build();

    private static final Pattern PREBUILT_GROOVY_VERSION = Pattern.compile("^\\d+\\.\\d+-2\\..+$");

    @Override
    protected GradleDependencies mapGradleDependencies(GradleDependencies gradleDependencies) {
        gradleDependencies.getDependencies().forEach(this::updateFromPomProperties);
        gradleDependencies.getDependencies().forEach(this::fixVersion);
        gradleDependencies.getDependencies().forEach(this::updateGroup);
        gradleDependencies.getDependencies().forEach(this::updateBomDependencyId);

        var depIdsWithoutGroup = gradleDependencies.getDependencies()
            .keySet()
            .stream()
            .filter(id -> id.getGroup().isEmpty())
            .map(String::valueOf)
            .toList();
        if (!depIdsWithoutGroup.isEmpty()) {
            throw new IllegalStateException("Can't determine groups for:\n  " + join("\n  ", depIdsWithoutGroup));
        }

        fixSnapshotDependencies(gradleDependencies);

        return gradleDependencies;
    }

    @SneakyThrows
    private void updateFromPomProperties(GradleDependencyId depId, GradleDependencyInfo depInfo) {
        var pomProperties = getPomProperties(depId, depInfo);
        if (pomProperties == null) {
            return;
        }

        var group = pomProperties.getProperty("groupId");
        if (group != null) {
            depId.setGroup(group);
        }
    }

    private void fixVersion(GradleDependencyId depId, GradleDependencyInfo depInfo) {
        var depNamePrefix = depId.getName() + "-";

        if (depNamePrefix.startsWith("jspecify-")) {
            depId.setVersion(
                substringBefore(depId.getVersion(), "-no-module-annotation")
            );
            return;
        }
    }

    private void updateGroup(GradleDependencyId depId, GradleDependencyInfo depInfo) {
        if (!depId.getGroup().isEmpty()) {
            return;
        }

        var depNamePrefix = depId.getName() + "-";


        for (var depNameToGroupEntry : DEP_NAME_TO_GROUP.entrySet()) {
            var baseDepName = depNameToGroupEntry.getKey();
            if (depNamePrefix.startsWith(baseDepName + "-")) {
                var group = depNameToGroupEntry.getValue();
                depId.setGroup(group);
                return;
            }
        }


        if (depNamePrefix.startsWith("groovy-")) {
            if (PREBUILT_GROOVY_VERSION.matcher(depId.getVersion()).matches()) {
                depId.setGroup(GRADLE_API_PUBLISH_GROUP); // some prebuilt groovy from Gradle
                depInfo.setSyntheticGroup(true);
            } else if (compareVersions(depId.getVersion(), "4") >= 0) {
                depId.setGroup("org.apache.groovy");
            } else {
                depId.setGroup("org.codehaus.groovy");
            }
            return;
        }


        if (depNamePrefix.startsWith("gradle-")
            || depNamePrefix.startsWith("local-groovy-")
            || depNamePrefix.startsWith("native-platform-")
        ) {
            depId.setGroup(GRADLE_API_PUBLISH_GROUP);
            return;
        }


        var depFile = Optional.ofNullable(depInfo.getPath())
            .map(this::getProjectRelativeFile)
            .orElse(null);

        if (depNamePrefix.startsWith("annotations-") && depFile != null) {
            var hasJetbrainsNonNull = getZipFileEntryNames(depFile).stream()
                .anyMatch("org/jetbrains/annotations/NotNull.class"::equals);
            if (hasJetbrainsNonNull) {
                depId.setGroup("org.jetbrains");
            }
            return;
        }

        if (depNamePrefix.startsWith("core-") && depFile != null) {
            var hasJdkCoreClasses = getZipFileEntryNames(depFile).stream()
                .anyMatch(it -> it.startsWith("org/eclipse/jdt/core/") && it.endsWith(".class"));
            if (hasJdkCoreClasses) {
                depId.setGroup("org.eclipse.jdt");
            }
            return;
        }
    }

    private void updateBomDependencyId(GradleDependencyId depId, GradleDependencyInfo depInfo) {
        if (depInfo.getBom() != null) {
            return;
        }

        if (depId.getGroup().equals("org.apache.groovy")
            || depId.getGroup().equals("org.codehaus.groovy")
        ) {
            if (compareVersions(depId.getVersion(), "2.4.19") >= 0) {
                depInfo.setBom(depId.withName("groovy-bom"));
            }
            return;
        }

        if (depId.getGroup().equals("org.jetbrains.kotlin")) {
            if (compareVersions(depId.getVersion(), "1.3.20") >= 0) {
                depInfo.setBom(depId.withName("kotlin-bom"));
            }
            return;
        }

        if (depId.getGroup().equals("org.slf4j")) {
            if (compareVersions(depId.getVersion(), "2.0.8") >= 0) {
                depInfo.setBom(depId.withName("slf4j-bom"));
            }
            return;
        }

        if (depId.getGroup().equals("org.ow2.asm")) {
            if (compareVersions(depId.getVersion(), "9.3") >= 0) {
                depInfo.setBom(depId.withName("asm-bom"));
            }
            return;
        }
    }


    private void fixSnapshotDependencies(GradleDependencies gradleDependencies) {
        var deps = gradleDependencies.getDependencies();
        var snapshotIds = deps.keySet().stream()
            .filter(id -> id.getVersion().endsWith("-SNAPSHOT"))
            .collect(toCollection(LinkedHashSet::new));

        deps.forEach((depId, depInfo) -> {
            if (snapshotIds.contains(depId)) {
                return;
            }

            var pomProperties = getPomProperties(depId, depInfo);
            if (pomProperties == null) {
                return;
            }

            var version = pomProperties.getProperty("version");
            if (version != null && version.endsWith("-SNAPSHOT")) {
                snapshotIds.add(depId);
            }
        });

        snapshotIds.forEach(snapshotId -> {
            snapshotId.setVersion(
                substringBefore(snapshotId.getVersion(), "-SNAPSHOT")
            );

            snapshotId.setGroup(GRADLE_API_PUBLISH_GROUP);
            deps.get(snapshotId).setSyntheticGroup(true);
        });
    }


    @Nullable
    @SneakyThrows
    private Properties getPomProperties(GradleDependencyId depId, GradleDependencyInfo depInfo) {
        var depFile = Optional.ofNullable(depInfo.getPath())
            .map(this::getProjectRelativeFile)
            .orElse(null);
        if (depFile == null) {
            return null;
        }

        var pomPropertiesEntryName = getZipFileEntryNames(depFile).stream()
            .filter(name -> name.startsWith("META-INF/maven/")
                && name.endsWith("/" + depId.getName() + "/pom.properties")
            )
            .findFirst()
            .orElse(null);
        if (pomPropertiesEntryName != null) {
            var properties = new Properties();
            try (
                var zipFile = new ZipFile(depFile, UTF_8);
                var in = zipFile.getInputStream(zipFile.getEntry(pomPropertiesEntryName))
            ) {
                properties.load(in);
            }

            return properties;
        }

        return null;
    }

}
