package build

import java.util.stream.Collectors
import javax.inject.Inject
import org.gradle.api.file.DuplicatesStrategy

class SourcesJar extends BaseJarTask {

    private static List<String> ALLOWED_EXTENSIONS = [
        'java',
        'kt',
        'kts',
        'groovy',
        'scala',
    ]

    @Inject
    SourcesJar(ClassesJar classesJar) {
        dependsOn(classesJar)

        archiveClassifier.set('sources')

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        onlyIf {
            filterSources(classesJar)
            return true
        }
    }

    private void filterSources(ClassesJar classesJar) {
        List<String> entryPrefixes = ZipUtils.getZipEntryNames(classesJar.archiveFile.get().asFile).stream()
            .filter { it.endsWith('.class') }
            .map { it.contains('/') ? it.substring(0, it.lastIndexOf('/') + 1) : '' }
            .distinct()
            .collect(Collectors.toList())

        if (entryPrefixes.isEmpty()) {
            exclude('**/*')

        } else {
            for (String entryPrefix : entryPrefixes) {
                for (String extension : ALLOWED_EXTENSIONS) {
                    include(entryPrefix + "*.$extension")
                }
            }
        }
    }

}
