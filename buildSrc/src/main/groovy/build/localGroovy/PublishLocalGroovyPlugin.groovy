package build.localGroovy

import build.BasePublishPlugin
import build.ZipUtils
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.publish.maven.internal.publication.MavenPomInternal

class PublishLocalGroovyPlugin extends BasePublishPlugin {

    @Override
    protected String getPublicationName() {
        return "localGroovy"
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    protected ConfigurationResult configurePom(MavenPomInternal pom) {
        def gradleApiInfo = this.gradleApiInfo
        File gradleHomeDir = this.gradleHomeDir
        List<File> localGroovyFiles = gradleApiInfo.localGroovyFiles
            ?.collect { stringToFile(it) }
            ?.findAll { doesPathStartWith(it, gradleHomeDir) }
            ?: []
        if (localGroovyFiles.isEmpty()) {
            return ConfigurationResult.SKIP
        }

        for (File localGroovyFile : localGroovyFiles) {
            String name = localGroovyFile.name

            boolean matches = false
            matches = matches || Pattern.compile(/(groovy(?:-[^-]+)?)-(\d+(?:\.\d+)*)\.jar/).with { pattern ->
                Matcher matcher = pattern.matcher(name)
                if (matcher.matches()) {
                    String artifactId = matcher.group(1)
                    String version = matcher.group(2)
                    pom.apiDependencies.add(
                        newMavenDependency(
                            'org.codehaus.groovy',
                            artifactId,
                            version
                        ) {
                            it.excludeRules.add(newExcludeRule('*', '*'))
                        }
                    )
                    return true
                }
                return false
            }

            matches = matches || Pattern.compile(/groovy-all-\d+(?:\.\d+)*-(\d+(?:\.\d+)*)\.jar/).with { pattern ->
                Matcher matcher = pattern.matcher(name)
                if (matcher.matches()) {
                    String version = matcher.group(1)
                    pom.apiDependencies.add(
                        newMavenDependency(
                            'org.codehaus.groovy',
                            'groovy-all',
                            version
                        ) {
                            it.excludeRules.add(newExcludeRule('org.codehaus.groovy', 'groovy-macro'))
                            it.excludeRules.add(newExcludeRule('org.apache.ant', '*'))
                            it.excludeRules.add(newExcludeRule('commons-cli', '*'))
                            it.excludeRules.add(newExcludeRule('info.picocli', '*'))
                            it.excludeRules.add(newExcludeRule('com.thoughtworks.qdox', '*'))
                            it.excludeRules.add(newExcludeRule('jline', '*'))
                            it.excludeRules.add(newExcludeRule('junit', '*'))
                            it.excludeRules.add(newExcludeRule('org.junit.jupiter', '*'))
                            it.excludeRules.add(newExcludeRule('org.junit.platform', '*'))
                            it.excludeRules.add(newExcludeRule('org.testng', '*'))
                        }
                    )
                    if (compareVersions(version, '2.5') >= 0) {
                        pom.apiDependencies.add(
                            newMavenDependency(
                                'org.codehaus.groovy',
                                'groovy-dateutil',
                                version
                            )
                        )
                    }
                    return true
                }
                return false
            }

            matches = matches || Pattern.compile(/(javaparser-core)-(\d+(?:\.\d+)*)\.jar/).with { pattern ->
                Matcher matcher = pattern.matcher(name)
                if (matcher.matches()) {
                    String artifactId = matcher.group(1)
                    String version = matcher.group(2)
                    pom.apiDependencies.add(
                        newMavenDependency(
                            'com.github.javaparser',
                            artifactId,
                            version
                        )
                    )
                    return true
                }
                return false
            }

            if (!matches) {
                throw new IllegalStateException("Unsupported library file: $localGroovyFile")
            }
        }

        return ConfigurationResult.PUBLISH
    }

    @Override
    protected void validatePublished(Configuration configuration) {
        List<ModuleComponentIdentifier> resolvedModuleComponentIdentifiers = configuration
            .resolvedConfiguration
            .resolvedArtifacts
            .collect { it.id.componentIdentifier }
            .findAll { it instanceof ModuleComponentIdentifier }
            .collect { (ModuleComponentIdentifier) it }

        // Has 'groovy' or 'groovy-all' dependencies
        assert resolvedModuleComponentIdentifiers.any {
            boolean isGroovy = it.group == 'org.codehaus.groovy'
            isGroovy &= it.module == 'groovy' || it.module == 'groovy-all'
            return isGroovy
        }

        // Has only expected dependencies
        assert resolvedModuleComponentIdentifiers.every {
            boolean isExpected = it.group == 'org.codehaus.groovy'
            if (isVersionInRange('7', gradleApiVersion, '7.0.9999')) {
                isExpected |= "${it.group}:${it.module}" == 'com.github.javaparser:javaparser-core'
            }
            return isExpected
        }

        // Has expected classes
        Set<String> zipsEntryNames = ZipUtils.getZipsEntryNames(configuration)
        assert zipsEntryNames.contains('groovy/lang/Closure.class')
        assert zipsEntryNames.contains('groovy/lang/GString.class')
        assert zipsEntryNames.contains('groovy/json/JsonOutput.class')
        assert zipsEntryNames.contains('groovy/json/JsonSlurper.class')
    }

}
