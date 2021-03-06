# Gradle API artifacts

Gradle API artifacts for plugins development.

## Usage

The artifacts are published to [a separate GitHub organisation](https://github.com/orgs/remal-gradle-api/packages), as it allows to republish them by recreating the organisation. It is unlikely that this will happen, however, it mitigates risks of broken artifacts publishing.

### Register repository

```groovy
repositories {
  maven {
    name = 'GradleAPI'
    url = uri('https://maven.pkg.github.com/remal-gradle-api/packages')
    credentials { // GitHub repositories don't allow anonymous access, that's why you have to use some credentials
      username = System.getenv('GITHUB_ACTOR')
      password = System.getenv('GITHUB_TOKEN')
    }
  }
}
```

### Use dependencies

#### Local Groovy

```groovy
dependencies {
  compileOnly "name.remal.gradle-api:local-groovy:${GradleVersion.current().version}"
}
```

#### Gradle API

```groovy
dependencies {
  compileOnly "name.remal.gradle-api:gradle-api:${GradleVersion.current().version}"
}
```

This dependency includes [local Groovy](#local-groovy).

#### Gradle Test Kit

```groovy
dependencies {
  testImplementation "name.remal.gradle-api:gradle-test-kit:${GradleVersion.current().version}"
}
```

This dependency includes [Gradle API](#gradle-api).

## Motivation

Unfortunately, Gradle team doesn't publish Gradle API artifacts somewhere, so plugin developers have to rely on dependencies, provided by Gradle itself ([`localGroovy()`](https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/dsl/DependencyHandler.html#localGroovy--), [`gradleApi()`](https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/dsl/DependencyHandler.html#gradleApi--), [`gradleTestKit()`](https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/dsl/DependencyHandler.html#gradleTestKit--)). However, such approach has some drawbacks:

* You can't run unit and functional tests against Gradle version different from Gradle installation.
* You can't use build the project with Gradle API dependencies of version different from Gradle installation.
* If you use `bin` distribution, you don't have access to sources.
* Even if you use `all` distribution, sometimes IDE (Intellij IDEA) fails attaching sources to Gradle API dependencies.

All these issues can be eliminated by publishing Gradle API artifacts to some Maven repository, and use them as external dependencies. This project does exactly that.

## Principles

The requirement level keywords "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" used in this document (case insensitive) are to be interpreted as described in [RFC 2119](https://www.ietf.org/rfc/rfc2119.txt).

### MUST publish artifacts for all Gradle release versions greater than 3.0

Artifacts for all Gradle release versions greater than 3.0 MUST be published.

Version 3.0 was chosen as a compromise between maximum Gradle versions support and difficulty of artifacts extraction.

The project uses Gradle Test Kit to download Gradle distributions and extract artifacts. Gradle Test Kit [requires](https://docs.gradle.org/current/userguide/third_party_integration.html#sec:embedding_compatibility) at least Gradle 2.6.

As `FilteringClassLoader` dependency is used to cut down Gradle API dependencies, the extraction process requires at least Gradle 3.0.

### SHOULD publish artifacts for release-candidates Gradle versions

Being able to use release-candidates dependencies allows testing plugin against future Gradle versions.

However, it doesn't make sense to publish old RC versions, so it's decided to publish only active RC version, if there is one at the moment of build.

### MUST check for new Gradle versions automatically

There should be scheduled CI/CD job that publish all unpublished versions.

### SHOULD check for new Gradle versions at least once a week

The scheduled CI/CD job SHOULD run at least once a week.

Currently, it's configured to run this job once a day.

### MUST follow native Gradle separation of `localGroovy`, `gradleApi` and `gradleTestKit` dependencies

Published artifacts MUST follow native Gradle separation of `localGroovy`, `gradleApi` and `gradleTestKit` dependencies, as it's expected by plugin authors.

### MUST provide correct transitive Gradle native dependencies

`gradleApi` dependency MUST provide `localGroovy` as a transitive dependency.

`gradleTestKit` dependency MUST provide `gradleApi` as a transitive dependency.

### SHOULD include external dependencies as transitive dependencies

Generated Gradle API JAR has some external Gradle dependency classes (JSR305, Slf4j, etc...).

Such classes SHOULD be removed from published artifacts and such dependencies should be added as transitive dependencies.

### MUST publish Gradle API sources JARs

As it's much harder to develop a Java project with dependencies without published sources, sources JARs MUST be published for all published artifacts.

### SHOULD publish sources of only used classes

`all` distribution provides sources for much more classes, than used in Gradle API. Unused sources SHOULD be removed to reduce sources JAR size.
