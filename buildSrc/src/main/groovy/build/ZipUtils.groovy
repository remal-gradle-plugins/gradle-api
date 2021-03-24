package build

import groovy.transform.Immutable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

abstract class ZipUtils {

    private static final ConcurrentMap<FileCacheKey, List<String>> RESOURCE_NAMES_CACHE = new ConcurrentHashMap<>()

    static List<String> getZipEntryNames(File archiveFile) {
        return RESOURCE_NAMES_CACHE.computeIfAbsent(new FileCacheKey(archiveFile.absolutePath, archiveFile.lastModified())) { key ->
            new ZipFile(key.path).withCloseable { zipFile ->
                return zipFile.stream()
                    .map { (ZipEntry) it }
                    .filter { !it.directory }
                    .map { it.name }
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList())
            }
        }
    }


    static Properties loadProperties(File archiveFile, String resourceName) {
        archiveFile = archiveFile.absoluteFile
        new ZipFile(archiveFile).withCloseable { zipFile ->
            ZipEntry zipEntry = zipFile.getEntry(resourceName)
            if (zipEntry == null) {
                throw new IllegalStateException("Resource can't be found in $archiveFile: $resourceName")
            }
            zipFile.getInputStream(zipEntry).withCloseable { inputStream ->
                Properties properties = new Properties()
                properties.load(inputStream)
                return properties
            }
        }
    }


    @Immutable
    private static class FileCacheKey {
        String path
        long lastModified
    }

    private ZipUtils() {
    }

}
