package build.collect

import static java.lang.management.ManagementFactory.getRuntimeMXBean
import static java.nio.file.Files.createDirectories
import static org.gradle.util.GUtil.loadProperties

import build.BaseGradleApiTask
import java.util.regex.Pattern
import javax.annotation.Nullable
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion

@CacheableTask
class CollectGradleApiInfo extends BaseGradleApiTask {

    @OutputFile
    File getInfoJsonFile() {
        return project.file("${project.buildDir}/gradle-api-info/${version}.json")
    }


    @Input
    String version = GradleVersion.current().version

    void setVersion(@Nullable Object version) {
        if (version == null) {
            setVersion(GradleVersion.current().version)
        } else if (version instanceof Provider) {
            setVersion(version.get())
        } else if (version instanceof GradleVersion) {
            setVersion(version.version)
        } else if (version instanceof CharSequence) {
            this.version = version.toString()
        } else {
            throw new UnsupportedOperationException("Unsupported version class: ${version.class}")
        }
    }


    @Internal
    protected GradleVersion getGradleVersion() {
        return GradleVersion.version(version)
    }


    private static final Pattern CURRENT_VERSION_PATTERN = Pattern.compile(
        /\b${Pattern.quote(GradleVersion.current().version)}\b/
    )

    private static final Pattern DISTRIBUTION_TYPE_PATTERN = Pattern.compile(
        /\bbin(\.[^\/]+)/
    )

    @Internal
    protected URI getDistributionUri() {
        URI defaultDistributionUri = new URI("https://services.gradle.org/distributions/gradle-${version}-all.zip")

        File gradleWrapperPropertiesFile = project.file("${project.rootDir}/gradle/wrapper/gradle-wrapper.properties")
        if (!gradleWrapperPropertiesFile.exists()) {
            return defaultDistributionUri
        }

        Properties gradleWrapperProperties = loadProperties(gradleWrapperPropertiesFile)
        String distributionUrlOriginal = gradleWrapperProperties.getProperty('distributionUrl')
        if (distributionUrlOriginal == null || distributionUrlOriginal.isEmpty()) {
            return defaultDistributionUri
        }

        String distributionUri = CURRENT_VERSION_PATTERN.matcher(distributionUrlOriginal).replaceAll(version)
        distributionUri = DISTRIBUTION_TYPE_PATTERN.matcher(distributionUri).replaceAll('all$1')
        return new URI(distributionUri)
    }


    private static final boolean IS_IN_DEBUG = getRuntimeMXBean().getInputArguments().toString().contains("jdwp")

    @TaskAction
    void execute() {
        println "Collecting Gradle API info for version ${version}"

        File outputJsonFile = this.infoJsonFile
        if (outputJsonFile.isFile()) {
            outputJsonFile.delete()
        } else {
            outputJsonFile.deleteDir()
        }
        createDirectories(outputJsonFile.parentFile.toPath())

        File tempDir = project.file("${temporaryDir}/${version}")
        tempDir.deleteDir()
        copyResourceTo(
            '/collectGradleApiInfo/settings.gradle',
            project.file("${tempDir}/settings.gradle")
        )
        copyResourceTo(
            '/collectGradleApiInfo/build.gradle',
            project.file("${tempDir}/build.gradle")
        )

        BuildResult buildResult = GradleRunner.create()
            .withGradleDistribution(distributionUri)
            .withDebug(IS_IN_DEBUG)
            .withProjectDir(tempDir)
            .withArguments(
                'collectGradleApiInfo',
                "-Poutput-json-file=${outputJsonFile}",
                '--quiet',
                '-Dorg.gradle.daemon=false',
            )
            .build()

        println buildResult.output

        didWork = true
    }

}
