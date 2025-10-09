package build.utils;

import static build.Constants.FALLBACK_JAVA_VERSION;
import static build.Constants.MIN_GRADLE_VERSION_TO_JAVA_VERSION;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newOutputStream;
import static java.util.Objects.requireNonNull;
import static java.util.jar.Attributes.Name.MANIFEST_VERSION;
import static java.util.jar.JarFile.MANIFEST_NAME;
import static java.util.zip.Deflater.DEFLATED;

import com.google.common.io.MoreFiles;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import lombok.SneakyThrows;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.util.GradleVersion;
import org.intellij.lang.annotations.Language;
import org.jspecify.annotations.Nullable;

public abstract class Utils {

    private static final int DELETE_ATTEMPTS = 5;

    @SuppressWarnings("BusyWait")
    @SneakyThrows
    public static void deleteRecursively(Path path) {
        for (var attempt = 1; ; attempt++) {
            try {
                if (exists(path)) {
                    MoreFiles.deleteRecursively(path, ALLOW_INSECURE);
                }
                return;

            } catch (IOException exception) {
                if (attempt >= DELETE_ATTEMPTS) {
                    throw exception;
                } else {
                    // If we have some file descriptor leak, calling GC can help us, as it can execute finalizers
                    // which close file descriptors.
                    System.gc();
                    Thread.sleep(100L * attempt);
                }
            }
        }
    }

    @SuppressWarnings("ConstantValue")
    public static void tryToDeleteRecursively(Path path) {
        try {
            deleteRecursively(path);
        } catch (Throwable exception) {
            if (exception instanceof IOException) {
                // do nothing
            } else {
                throw exception;
            }
        }
    }


    @SneakyThrows
    public static Path createCleanDirectory(Path path) {
        deleteRecursively(path);
        createDirectories(path);
        return path;
    }

    @SuppressWarnings("ConstantValue")
    public static Path tryToCreateCleanDirectory(Path path) {
        try {
            createCleanDirectory(path);
        } catch (Throwable exception) {
            if (exception instanceof IOException) {
                // do nothing
            } else {
                throw exception;
            }
        }
        return path;
    }


    public static JavaLanguageVersion getGradleJvmVersion(GradleVersion gradleVersion) {
        gradleVersion = gradleVersion.getBaseVersion();

        for (var entry : MIN_GRADLE_VERSION_TO_JAVA_VERSION.entrySet()) {
            if (gradleVersion.compareTo(entry.getKey()) >= 0) {
                return entry.getValue();
            }
        }

        return FALLBACK_JAVA_VERSION;
    }

    public static JavaLanguageVersion getGradleJvmVersion(String gradleVersion) {
        return getGradleJvmVersion(GradleVersion.version(gradleVersion));
    }


    private static final VersionParser PARSER = new VersionParser();
    private static final Comparator<Version> VERSION_COMPARATOR = new DefaultVersionComparator().asVersionComparator();

    public static int compareVersions(String versionString1, String versionString2) {
        var version1 = requireNonNull(PARSER.transform(versionString1));
        var version2 = requireNonNull(PARSER.transform(versionString2));
        return VERSION_COMPARATOR.compare(version1, version2);
    }


    @Language("Groovy")
    public static String createGradleContent(@Language("Groovy") String content, Map<String, Object> substitutions) {
        for (var entry : substitutions.entrySet()) {
            var key = entry.getKey();
            var value = unwrapProviders(entry.getValue());

            if (value instanceof Path path) {
                value = path.toAbsolutePath().toString().replace('\\', '/');
            } else if (value instanceof File file) {
                value = file.getAbsolutePath().replace('\\', '/');
            }

            content = content.replace('#' + key + '#', String.valueOf(value));
        }
        return content;
    }

    @Language("Groovy")
    public static String createGradleContent(@Language("Groovy") String content) {
        return createGradleContent(content, Map.of());
    }

    @Nullable
    private static Object unwrapProviders(@Nullable Object object) {
        while (true) {
            switch (object) {
                case null -> {
                    return null;
                }
                case Provider<?> provider -> object = provider.getOrNull();
                case FileSystemLocation location -> object = location.getAsFile();
                default -> {
                    return object;
                }
            }
        }
    }


    public static void copyJarEntries(File inFile, File outFile, Collection<String> entryNames) {
        copyZipEntries(inFile, outFile, entryNames, false);
    }

    @SneakyThrows
    private static void copyZipEntries(File inFile, File outFile, Collection<String> entryNames, boolean addManifest) {
        createDirectories(outFile.toPath().getParent());

        try (
            var inputZipFile = new ZipFile(inFile, UTF_8);
            var out = new ZipOutputStream(newOutputStream(outFile.toPath()), UTF_8)
        ) {
            out.setMethod(DEFLATED);
            out.setLevel(9);
            for (var name : entryNames) {
                var inputEntry = inputZipFile.getEntry(name);
                if (inputEntry == null) {
                    continue;
                }

                var outputEntry = new ZipEntry(inputEntry);
                out.putNextEntry(outputEntry);
                try (var in = inputZipFile.getInputStream(inputEntry)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }

            if (addManifest && !entryNames.contains(MANIFEST_NAME)) {
                var manifest = new Manifest();
                manifest.getMainAttributes().put(MANIFEST_VERSION, "1.0");
                out.putNextEntry(new ZipEntry(MANIFEST_NAME));
                manifest.write(out);
                out.closeEntry();
            }
        }

    }


    public static String substringBefore(String string, String needle) {
        var lastDelimPos = string.indexOf(needle);
        return lastDelimPos > 0 ? string.substring(0, lastDelimPos) : string;
    }

    public static String substringBeforeLast(String string, String needle) {
        var lastDelimPos = string.lastIndexOf(needle);
        return lastDelimPos > 0 ? string.substring(0, lastDelimPos) : string;
    }

}
