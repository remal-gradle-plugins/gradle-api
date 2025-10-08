package build.tasks;

import static build.Constants.GRADLE_API_PUBLISH_GROUP;
import static build.utils.Utils.compareVersions;
import static build.utils.ZipUtils.getZipFileEntryNames;
import static java.lang.String.join;
import static java.nio.charset.StandardCharsets.UTF_8;

import build.dto.GradleDependencies;
import build.dto.GradleDependencyId;
import build.dto.GradleDependencyInfo;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipFile;
import lombok.SneakyThrows;
import org.gradle.api.tasks.CacheableTask;

@CacheableTask
public abstract class CompleteDependencies extends AbstractMappingDependenciesInfoTask {

    private static final Map<String, String> DEP_NAME_TO_GROUP = ImmutableMap.<String, String>builder()
        .put("kotlin", "org.jetbrains.kotlin")
        .put("ant", "org.apache.ant")
        .put("jspecify", "org.jspecify")
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

    @Override
    protected GradleDependencies mapGradleDependencies(GradleDependencies gradleDependencies) {
        gradleDependencies.getDependencies().forEach(this::updateFromPomProperties);
        gradleDependencies.getDependencies().forEach(this::fixVersion);
        gradleDependencies.getDependencies().forEach(this::updateGroup);
        gradleDependencies.getDependencies().forEach(this::updateBomDependencyId);

        var depIdsWithoutGroup = gradleDependencies.getDependencies().keySet().stream()
            .filter(depId -> depId.getGroup().isEmpty())
            .map(String::valueOf)
            .toList();
        if (!depIdsWithoutGroup.isEmpty()) {
            throw new IllegalStateException(
                "Can't determine groups for:\n  " + join("\n  ", depIdsWithoutGroup)
            );
        }

        fixSnapshotDependencies(gradleDependencies);

        return gradleDependencies;
    }

    @SneakyThrows
    private void updateFromPomProperties(GradleDependencyId depId, GradleDependencyInfo depInfo) {
        var depFile = Optional.ofNullable(depInfo.getPath())
            .map(this::getGradleFile)
            .orElse(null);
        if (depFile == null) {
            return;
        }

        var pomPropertiesEntryName = getZipFileEntryNames(depFile).stream()
            .filter(name ->
                name.startsWith("META-INF/maven/")
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

            var group = properties.getProperty("groupId");
            if (group != null) {
                depId.setGroup(group);
            }

            var version = properties.getProperty("version");
            if (version != null) {
                depId.setVersion(version);
            }
        }
    }

    private void fixVersion(GradleDependencyId depId, GradleDependencyInfo depInfo) {
        var depNamePrefix = depId.getName() + "-";

        if (depNamePrefix.startsWith("jspecify-")) {
            var version = depId.getVersion();
            if (version.endsWith("-no-module-annotation")) {
                depId.setVersion(version.substring(0, version.length() - "-no-module-annotation".length()));
            }
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
            if (compareVersions(depId.getVersion(), "4") >= 0) {
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
            .map(this::getGradleFile)
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
            var hasJetbrainsNonNull = getZipFileEntryNames(depFile).stream()
                .anyMatch(it -> it.startsWith("org/eclipse/jdt/core/") && it.endsWith(".class"));
            if (hasJetbrainsNonNull) {
                depId.setGroup("org.eclipse.jdt");
            }
            return;
        }
    }

    private void updateBomDependencyId(GradleDependencyId depId, GradleDependencyInfo depInfo) {
        if (depInfo.getBom() != null) {
            return;
        }

        var depNamePrefix = depId.getName() + "-";


        if (depNamePrefix.startsWith("groovy-")) {
            if (compareVersions(depId.getVersion(), "2.4.19") >= 0) {
                depInfo.setBom(depId.withName("groovy-bom"));
            }
            return;
        }

        if (depNamePrefix.startsWith("kotlin-")) {
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

        if (depNamePrefix.startsWith("asm-")) {
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
            .toList();
        for (var snapshotId : snapshotIds) {
            var newId = gradleDependencies.getGradleDependencyIdByPathOrName(
                "gradle-snapshot-dependency-" + snapshotId.getName()
            );
            newId.setVersion(gradleDependencies.getGradleVersion());
            newId.setGroup(GRADLE_API_PUBLISH_GROUP);

            deps.values().forEach(info -> {
                if (info.getDependencies().remove(snapshotId)) {
                    info.getDependencies().add(newId);
                }
            });

            var info = deps.remove(snapshotId);
            deps.put(newId, info);
        }
    }

}
