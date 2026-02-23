plugins {
    `java-library`
    `java-gradle-plugin`
    id("name.remal.lombok") version "3.1.6"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val allConstraints by configurations.creating conf@{
    isCanBeResolved = false
    configurations
        .matching { it !== this@conf }
        .configureEach { extendsFrom(this@conf) }
}

dependencies {
    allConstraints(platform("com.fasterxml.jackson:jackson-bom:2.21.1"))
    allConstraints(platform("org.ow2.asm:asm-bom:9.9.1"))

    compileOnly("org.jetbrains:annotations:26.1.0")

    compileOnly(gradleTestKit())

    implementation("com.google.guava:guava:33.5.0-jre")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-guava")
    implementation("org.ow2.asm:asm-tree")
    implementation("org.apache.maven:maven-model:3.9.12")
    implementation("org.assertj:assertj-core:3.27.7")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = java.toolchain.languageVersion.map(JavaLanguageVersion::asInt)
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

gradlePlugin {
    plugins {
        create("build-logic") {
            id = "build-logic"
            implementationClass = "build.BuildLogicPlugin"
        }
    }
}
