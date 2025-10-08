import build.tasks.ExtractGradleFiles
import build.tasks.PublishArtifactsToLocalBuildRepository

plugins {
    id("build-logic")
}


tasks.withType<ExtractGradleFiles>().configureEach { onlyIf { _ -> true } }
tasks.withType<PublishArtifactsToLocalBuildRepository>().configureEach { onlyIf { _ -> true } }


buildLogic {
    project.findProperty("gradle.version")?.toString()?.ifEmpty { null }?.run { gradleVersion = this }

    license license@{
        this@license.name = "MIT License"
        this@license.url = "https://choosealicense.com/licenses/mit/"
    }
}


dependencies {
    allConstraints(platform("org.junit:junit-bom:5.14.0"))


    testCompileOnly(gradleTestKit())

    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("com.google.guava:guava:33.5.0-jre")
    testImplementation("org.assertj:assertj-core:3.27.6")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    val expectedGradleVersion = buildLogic.gradleVersion
    onlyIf { _ ->
        environment("EXPECTED_GRADLE_VERSION", expectedGradleVersion.get())
        return@onlyIf true
    }

    testLogging {
        events("PASSED", "SKIPPED", "FAILED")
    }
}
