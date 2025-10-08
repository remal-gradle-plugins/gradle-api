package build.utils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableSequencedMap;
import static java.util.Collections.unmodifiableSequencedSet;
import static java.util.Comparator.comparing;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toCollection;

import com.google.common.base.Splitter;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import lombok.SneakyThrows;

public abstract class GradleModuleClasspathUtils {

    private static final SequencedMap<File, SequencedMap<String, GradleModuleInfo>> CACHE = new LinkedHashMap<>();

    private static synchronized SequencedMap<String, GradleModuleInfo> getModules(File file) {
        return CACHE.computeIfAbsent(file, GradleModuleClasspathUtils::readModules);
    }

    private static final Pattern INCLUDE_ENTRY = Pattern.compile("^([^/]+)-classpath\\.properties$");

    @SneakyThrows
    private static SequencedMap<String, GradleModuleInfo> readModules(File file) {
        var result = new LinkedHashMap<String, GradleModuleInfo>();
        try (var zipFile = new ZipFile(file, UTF_8)) {
            var entriesList = zipFile.stream()
                .filter(not(ZipEntry::isDirectory))
                .sorted(comparing(ZipEntry::getName))
                .toList();
            for (var entry : entriesList) {
                var includeMatcher = INCLUDE_ENTRY.matcher(entry.getName());
                if (!includeMatcher.matches()) {
                    continue;
                }
                var include = includeMatcher.group(1);

                var includeProperties = new Properties();
                try (var in = zipFile.getInputStream(entry)) {
                    includeProperties.load(in);
                }

                var scopePaths = new LinkedHashMap<String, SequencedSet<String>>();
                includeProperties.keySet().stream()
                    .map(String::valueOf)
                    .forEach(scope -> {
                        var pathsString = includeProperties.getProperty(scope);
                        var paths = Splitter.on(',').splitToStream(pathsString)
                            .map(String::trim)
                            .filter(not(String::isEmpty))
                            .collect(toCollection(LinkedHashSet::new));
                        scopePaths.put(scope, unmodifiableSequencedSet(paths));
                    });

                var info = new GradleModuleInfo(unmodifiableSequencedMap(scopePaths));
                result.put(include, info);
            }
        }
        return unmodifiableSequencedMap(result);
    }


    public record GradleModuleInfo(
        SequencedMap<String, SequencedSet<String>> scopePaths
    ) { }


    public static SequencedMap<String, GradleModuleInfo> getGradleClasspathModules(File file) {
        return getModules(file);
    }

}
