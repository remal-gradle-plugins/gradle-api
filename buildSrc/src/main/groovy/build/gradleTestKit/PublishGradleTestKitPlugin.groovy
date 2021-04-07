package build.gradleTestKit

import build.BasePublishPlugin
import build.ClassesJar
import build.ZipUtils
import build.gradleApi.PublishGradleApiPlugin
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.gradle.api.artifacts.Configuration

class PublishGradleTestKitPlugin extends BasePublishPlugin {

    @Override
    protected List<Class<? extends BasePublishPlugin>> getDependencyPluginClasses() {
        return [PublishGradleApiPlugin]
    }

    @Override
    protected String getPublicationName() {
        return "gradleTestKit"
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    protected ConfigurationResult configureClassesJar(ClassesJar classesJar) {
        def gradleApiInfo = this.gradleApiInfo
        List<File> gradleTestKitFiles = gradleApiInfo.gradleTestKitFiles
            ?.collect { stringToFile(it) }
            ?: []
        if (gradleTestKitFiles.isEmpty()) {
            return ConfigurationResult.SKIP
        }

        for (File gradleTestKitFile : gradleTestKitFiles) {
            classesJar.from(project.zipTree(gradleTestKitFile))
        }

        return ConfigurationResult.PUBLISH
    }

    @Override
    protected void validatePublished(Configuration configuration) {
        configuration.resolve()

        // Has expected classes
        Set<String> zipsEntryNames = ZipUtils.getZipsEntryNames(configuration)
        assert zipsEntryNames.contains('org/gradle/testkit/runner/GradleRunner.class')
    }

}
