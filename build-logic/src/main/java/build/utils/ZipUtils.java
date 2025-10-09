package build.utils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableSequencedSet;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toCollection;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import lombok.SneakyThrows;

public abstract class ZipUtils {

    private static final SequencedMap<File, ZipFileInfo> CACHE = new LinkedHashMap<>();

    private static synchronized ZipFileInfo getInfo(File file) {
        return CACHE.computeIfAbsent(file, ZipUtils::readInfo);
    }

    @SneakyThrows
    private static ZipFileInfo readInfo(File file) {
        try (var zipFile = new ZipFile(file, UTF_8)) {
            var fileEntryNames = zipFile.stream()
                .filter(not(ZipEntry::isDirectory))
                .map(ZipEntry::getName)
                .sorted()
                .collect(toCollection(LinkedHashSet::new));

            return new ZipFileInfo(
                unmodifiableSequencedSet(fileEntryNames)
            );
        }
    }


    public record ZipFileInfo(
        SequencedSet<String> fileEntryNames
    ) { }


    public static ZipFileInfo getZipFileInfo(File file) {
        return getInfo(file);
    }

    public static SequencedSet<String> getZipFileEntryNames(File file) {
        return getZipFileInfo(file).fileEntryNames();
    }

}
