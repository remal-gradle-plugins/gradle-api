package build.tasks;

import static build.Constants.GRADLE_API_BOM_NAME;
import static build.Constants.GRADLE_API_PUBLISH_GROUP;
import static build.utils.AsmUtils.getSourceFile;
import static build.utils.Utils.copyJarEntries;
import static build.utils.Utils.createCleanDirectory;
import static build.utils.Utils.substringBeforeLast;
import static build.utils.ZipUtils.getZipFileEntryNames;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.lang.Boolean.TRUE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.newOutputStream;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toCollection;
import static org.gradle.api.tasks.PathSensitivity.RELATIVE;

import build.dto.GradleDependencies;
import build.dto.GradleDependencyId;
import build.dto.GradleDependencyInfo;
import build.dto.GradlePublishedDependencies;
import build.dto.GradlePublishedDependencyInfo;
import build.utils.Json;
import build.utils.WithLocalBuildRepository;
import build.utils.WithPublishLicense;
import build.utils.ZipUtils;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import java.io.File;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipFile;
import lombok.SneakyThrows;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.gradle.api.Action;
import org.gradle.api.BuildCancelledException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;

@CacheableTask
public abstract class PublishArtifactsToLocalBuildRepository extends AbstractGradleFilesConsumerTask
    implements WithPublishLicense, WithLocalBuildRepository {

    @InputFile
    @PathSensitive(RELATIVE)
    public abstract RegularFileProperty getGradleDependenciesFile();

    @Input
    public abstract Property<Boolean> getPublishHashes();

    {
        getPublishHashes().convention(false);
    }


    @OutputDirectory
    @Override
    public abstract DirectoryProperty getLocalBuildRepository();

    {
        getLocalBuildRepository().convention(getLayout().getBuildDirectory().dir(getName()));
    }

    @OutputFile
    public abstract RegularFileProperty getGradlePublishedDependenciesJsonFile();

    {
        getGradlePublishedDependenciesJsonFile().convention(getLocalBuildRepository().file("info.json"));
    }


    {
        onlyIf(__ -> {
            getGradleDependenciesFile().finalizeValueOnRead();
            getLocalBuildRepository().finalizeValueOnRead();
            getGradlePublishedDependenciesJsonFile().finalizeValueOnRead();
            return true;
        });
    }


    @TaskAction
    public void execute() throws Exception {
        createCleanDirectory(getLocalBuildRepository().getAsFile().get().toPath());

        var outputFile = getGradlePublishedDependenciesJsonFile().getAsFile().get().toPath();
        deleteIfExists(outputFile);
        createDirectories(outputFile.getParent());

        var gradleDependencies = Json.JSON_READER.readValue(getGradleDependenciesFile().get().getAsFile(),
            GradleDependencies.class);

        var publishedDependencies = new GradlePublishedDependencies(gradleDependencies.getGradleVersion());


        publishGradleApiBom(gradleDependencies, publishedDependencies);

        gradleDependencies.getDependencies()
            .entrySet()
            .stream()
            .filter(entry -> entry.getKey().getGroup().equals(GRADLE_API_PUBLISH_GROUP))
            .forEach(entry -> publishDependency(gradleDependencies,
                entry.getKey(),
                entry.getValue(),
                publishedDependencies));


        Json.JSON_WRITER.writeValue(outputFile.toFile(), publishedDependencies);
    }


    @SneakyThrows
    private File publishPom(Action<Model> configure) {
        var pom = new Model();
        pom.setModelVersion("4.0.0");

        var pomLicense = new License();
        pomLicense.setName(getLicense().getName().getOrNull());
        pomLicense.setUrl(getLicense().getUrl().getOrNull());
        pom.addLicense(pomLicense);

        configure.execute(pom);

        var outputFile = getLocalBuildRepository().getAsFile()
            .get()
            .toPath()
            .resolve(pom.getGroupId().replace('.', '/'))
            .resolve(pom.getArtifactId())
            .resolve(pom.getVersion())
            .resolve(pom.getArtifactId() + "-" + pom.getVersion() + ".pom");
        getLogger().lifecycle("Creating {}", outputFile);
        createDirectories(outputFile.getParent());
        try (var out = newOutputStream(outputFile)) {
            new MavenXpp3Writer().write(out, pom);
        }

        publishHashesOf(outputFile.toFile());

        return outputFile.toFile();
    }

    private static Dependency createDependency(GradleDependencyId id, @Nullable String type, @Nullable String scope) {
        var dependency = new Dependency();
        dependency.setGroupId(id.getGroup());
        dependency.setArtifactId(id.getName());
        if (!id.getVersion().isEmpty()) {
            dependency.setVersion(id.getVersion());
        }
        dependency.setType(type);
        dependency.setScope(scope);
        return dependency;
    }

    private static Dependency createDependency(GradleDependencyId id) {
        return createDependency(id, null, null);
    }

    private File publishGradleApiBom(GradleDependencies gradleDependencies, GradlePublishedDependencies publishedDeps) {
        var bomId = gradleDependencies.getDependencyIdByPathOrName(GRADLE_API_BOM_NAME,
            gradleDependencies.getGradleVersion(),
            GRADLE_API_PUBLISH_GROUP);

        var pomFile = publishPom(pom -> {
            pom.setGroupId(bomId.getGroup());
            pom.setArtifactId(bomId.getName());
            pom.setVersion(bomId.getVersion());
            pom.setPackaging("pom");

            pom.setDependencyManagement(new DependencyManagement());
            var dependencyManagement = pom.getDependencyManagement().getDependencies();
            gradleDependencies.getDependencies().keySet().forEach(id -> {
                if (id.getGroup().equals(GRADLE_API_PUBLISH_GROUP)) {
                    dependencyManagement.add(createDependency(id));
                }
            });
        });

        publishedDeps.getDependencies()
            .put(bomId,
                new GradlePublishedDependencyInfo(getLocalBuildRepository().getAsFile()
                    .get()
                    .toPath()
                    .relativize(pomFile.toPath())));

        return pomFile;
    }

    private void publishDependency(
        GradleDependencies gradleDependencies,
        GradleDependencyId depId,
        GradleDependencyInfo depInfo,
        GradlePublishedDependencies publishedDeps
    ) {
        if (getBuildCancellationToken().isCancellationRequested()) {
            throw new BuildCancelledException();
        }

        publishPom(gradleDependencies, depId, depInfo, publishedDeps);
        var jarFile = publishJar(gradleDependencies, depId, depInfo, publishedDeps);
        if (jarFile != null) {
            publishSourcesJar(gradleDependencies, depId, jarFile, publishedDeps);
        }
    }

    private File publishPom(
        GradleDependencies gradleDependencies,
        GradleDependencyId id,
        GradleDependencyInfo info,
        GradlePublishedDependencies publishedDeps
    ) {
        var pomFile = publishPom(pom -> {
            pom.setGroupId(id.getGroup());
            pom.setArtifactId(id.getName());
            pom.setVersion(id.getVersion());
            pom.setPackaging(info.hasArtifact() ? "jar" : "pom");

            pom.setDependencyManagement(new DependencyManagement());
            var dependencyManagement = pom.getDependencyManagement().getDependencies();
            var dependencies = pom.getDependencies();

            var addedBoms = new LinkedHashSet<GradleDependencyId>();

            var gradleApiBomId = gradleDependencies.getDependencyIdByPathOrName(GRADLE_API_BOM_NAME,
                gradleDependencies.getGradleVersion(),
                GRADLE_API_PUBLISH_GROUP);
            if (id.getGroup().equals(GRADLE_API_PUBLISH_GROUP)) {
                if (addedBoms.add(gradleApiBomId)) {
                    dependencyManagement.add(createDependency(gradleApiBomId, "pom", "import"));
                }
            }

            info.getDependencies().forEach(depId -> {
                var bomId = Optional.ofNullable(gradleDependencies.getDependencies().get(depId))
                    .map(GradleDependencyInfo::getBom)
                    .orElse(null);
                if (bomId == null && depId.getGroup().equals(GRADLE_API_PUBLISH_GROUP)) {
                    bomId = gradleApiBomId;
                }
                if (bomId != null) {
                    dependencies.add(createDependency(depId.withVersion("")));
                    if (addedBoms.add(bomId)) {
                        dependencyManagement.add(createDependency(bomId, "pom", "import"));
                    }
                    return;
                }

                dependencies.add(createDependency(depId));
            });
        });

        publishedDeps.getDependencies()
            .put(id,
                new GradlePublishedDependencyInfo(getLocalBuildRepository().getAsFile()
                    .get()
                    .toPath()
                    .relativize(pomFile.toPath())));

        return pomFile;
    }

    @Nullable
    @SneakyThrows
    private File publishJar(
        GradleDependencies gradleDependencies,
        GradleDependencyId id,
        GradleDependencyInfo info,
        GradlePublishedDependencies publishedDeps
    ) {
        var file = Optional.ofNullable(info.getPath()).map(this::getProjectRelativeFile).orElse(null);
        if (file == null) {
            return null;
        }

        var entriesToExclude = gradleDependencies.getAllDependencies(id)
            .stream()
            .map(gradleDependencies.getDependencies()::get)
            .filter(Objects::nonNull)
            .map(GradleDependencyInfo::getPath)
            .filter(Objects::nonNull)
            .map(this::getProjectRelativeFile)
            .map(ZipUtils::getZipFileEntryNames)
            .flatMap(Collection::stream)
            .filter(not(PublishArtifactsToLocalBuildRepository::isNotFatJarEntry))
            .collect(toImmutableSet());

        var allEntries = getZipFileEntryNames(file);
        var entriesToInclude = allEntries.stream().filter(not(entriesToExclude::contains)).toList();

        var outputFile = getLocalBuildRepository().getAsFile()
            .get()
            .toPath()
            .resolve(id.getGroup().replace('.', '/'))
            .resolve(id.getName())
            .resolve(id.getVersion())
            .resolve(id.getName() + "-" + id.getVersion() + ".jar");
        getLogger().lifecycle("Creating {}", outputFile);
        copyJarEntries(file, outputFile.toFile(), entriesToInclude, getBuildCancellationToken());

        publishedDeps.getDependencies()
            .get(id)
            .setJarFilePath(getLocalBuildRepository().getAsFile().get().toPath().relativize(outputFile));

        publishHashesOf(outputFile.toFile());

        return outputFile.toFile();
    }

    @SneakyThrows
    private File publishSourcesJar(
        GradleDependencies gradleDependencies,
        GradleDependencyId id,
        File jarFile,
        GradlePublishedDependencies publishedDeps
    ) {
        var allEntries = getZipFileEntryNames(jarFile);
        var entryPrefixes = allEntries.stream().map(name -> {
            var prefix = getEntryPrefix(name);
            name = name.substring(prefix.length());
            name = substringBeforeLast(name, ".");
            name = substringBeforeLast(name, "$");
            return prefix + name;
        }).distinct().toList();

        var sourcesArchiveFile = getProjectRelativeFile(gradleDependencies.getSourcesArchiveFile());
        var allSourceEntries = getZipFileEntryNames(sourcesArchiveFile);
        var entriesToInclude = allSourceEntries.stream()
            .filter(not(PublishArtifactsToLocalBuildRepository::isNotFatJarEntry))
            .filter(name -> entryPrefixes.stream().anyMatch(name::startsWith))
            .collect(toCollection(LinkedHashSet::new));

        try (var zipFile = new ZipFile(jarFile, UTF_8)) {
            var classEntries = allEntries.stream().filter(name -> name.endsWith(".class")).toList();
            for (var entryName : classEntries) {
                var entry = requireNonNull(zipFile.getEntry(entryName));
                try (var in = zipFile.getInputStream(entry)) {
                    var sourceEntryName = getSourceFile(in);
                    if (sourceEntryName != null) {
                        entriesToInclude.add(sourceEntryName);
                    }
                }
            }

        }

        var outputFile = getLocalBuildRepository().getAsFile()
            .get()
            .toPath()
            .resolve(id.getGroup().replace('.', '/'))
            .resolve(id.getName())
            .resolve(id.getVersion())
            .resolve(id.getName() + "-" + id.getVersion() + "-sources.jar");
        getLogger().lifecycle("Creating {}", outputFile);
        copyJarEntries(sourcesArchiveFile, outputFile.toFile(), entriesToInclude, getBuildCancellationToken());

        publishedDeps.getDependencies()
            .get(id)
            .setSourcesJarFilePath(getLocalBuildRepository().getAsFile().get().toPath().relativize(outputFile));

        publishHashesOf(outputFile.toFile());

        return outputFile.toFile();
    }

    private static boolean isNotFatJarEntry(String entryName) {
        if (entryName.startsWith("META-INF/")) {
            return entryName.endsWith(".MF")
                || entryName.endsWith(".SF")
                || entryName.endsWith(".RSA")
                || entryName.endsWith(".DSA")
                || entryName.endsWith(".EC");

        }
        return false;
    }

    private static String getEntryPrefix(String name) {
        var lastSlashPos = name.lastIndexOf('/');
        return lastSlashPos > 0 ? name.substring(0, lastSlashPos + 1) : "";
    }


    @SuppressWarnings("deprecation")
    private void publishHashesOf(File file) {
        if (!TRUE.equals(getPublishHashes().getOrNull())) {
            return;
        }

        publishHashOf(file, Hashing.md5(), ".md5");
        publishHashOf(file, Hashing.sha1(), ".sha1");
        publishHashOf(file, Hashing.sha256(), ".sha256");
        publishHashOf(file, Hashing.sha512(), ".sha512");
    }

    @SneakyThrows
    private static void publishHashOf(File file, HashFunction hashFunction, String extension) {
        var hash = Files.asByteSource(file).hash(hashFunction).toString();
        var destPath = new File(file.getPath() + extension).toPath();
        try (var out = newOutputStream(destPath)) {
            out.write(hash.getBytes(UTF_8));
        }
    }

}
