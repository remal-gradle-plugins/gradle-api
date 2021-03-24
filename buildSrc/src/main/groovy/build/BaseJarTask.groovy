package build

import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar

abstract class BaseJarTask extends Jar {

    protected BaseJarTask() {
        group = BaseGradleApiTask.TASK_GROUP_NAME

        destinationDirectory.set(project.file("${project.buildDir}/lib"))

        includeEmptyDirs = false
        reproducibleFileOrder = true
        duplicatesStrategy = DuplicatesStrategy.FAIL

        exclude('module-info.class')

        exclude('META-INF/*.MF')
        exclude('META-INF/*.DSA')
        exclude('META-INF/*.SF')
        exclude('META-INF/maven/**')

        exclude('gradle-wrapper.jar')
    }

}
