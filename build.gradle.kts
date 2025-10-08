import build.tasks.ExtractGradleFiles
import build.tasks.PublishArtifactsToLocalBuildRepository
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("build-logic")
}


if (false) {
    tasks.withType<ExtractGradleFiles>().configureEach { onlyIf { false } }
    tasks.withType<PublishArtifactsToLocalBuildRepository>().configureEach { onlyIf { false } }
}


buildLogic {
    project.findProperty("gradle.version")?.toString()?.ifEmpty { null }?.run { gradleVersion = this }
    license license@{
        this@license.name = "MIT License"
        this@license.url = "https://choosealicense.com/licenses/mit/"
    }
}


java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val allConstraints by configurations.creating conf@{
    isCanBeResolved = false
    configurations.matching { it.isCanBeResolved }.configureEach { extendsFrom(this@conf) }
}

dependencies {
    allConstraints(platform("org.junit:junit-bom:5.14.0"))


    testCompileOnly(gradleTestKit())

    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("com.google.guava:guava:33.5.0-jre")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.encoding = "UTF-8"
    options.isDeprecation = true
    options.compilerArgs.addAll(listOf(
        "-parameters",
        "-Werror",
        "-Xlint:all",
        "-Xlint:-rawtypes",
        "-Xlint:-serial",
        "-Xlint:-processing",
        "-Xlint:-this-escape",
        "-Xlint:-options",
    ))
}

tasks.withType<Test>().configureEach {
    val gradleBaseVersion = objects.property<GradleVersion>().value(
        buildLogic.gradleVersion
            .map(GradleVersion::version)
            .map(GradleVersion::getBaseVersion)
    ).let { it.finalizeValueOnRead(); it }

    val jvmVersion = objects.property<Int>().value(
        gradleBaseVersion.map {
            if (it >= GradleVersion.version("9.0")) {
                18
            } else {
                8
            }
        }
    ).let { it.finalizeValueOnRead(); it }

    javaLauncher = javaToolchains.launcherFor {
        languageVersion.set(jvmVersion.map(JavaLanguageVersion::of))
    }

    onlyIf onlyIf@{
        if (jvmVersion.get() >= 9) {
            // see https://github.com/gradle/gradle/issues/18647
            jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
        }
        if (jvmVersion.get() >= 24) {
            // see https://github.com/gradle/gradle/issues/31625
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }

        return@onlyIf true
    }

    useJUnitPlatform()
    enableAssertions = true

    testLogging {
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = TestExceptionFormat.FULL
        stackTraceFilters("GROOVY")
        events("PASSED", "SKIPPED", "FAILED")
    }
}
