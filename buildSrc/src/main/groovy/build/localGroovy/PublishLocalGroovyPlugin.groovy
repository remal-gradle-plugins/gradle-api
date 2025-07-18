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
                    String group = getGroovyGroup(version)
                    pom.apiDependencies.add(
                        newMavenDependency(
                            group,
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
                    String group = getGroovyGroup(version)
                    pom.apiDependencies.add(
                        newMavenDependency(
                            group,
                            'groovy-all',
                            version
                        ) {
                            it.excludeRules.add(newExcludeRule(group, 'groovy-cli-picocli'))
                            it.excludeRules.add(newExcludeRule(group, 'groovy-docgenerator'))
                            it.excludeRules.add(newExcludeRule(group, 'groovy-groovysh'))
                            it.excludeRules.add(newExcludeRule(group, 'groovy-jmx'))
                            it.excludeRules.add(newExcludeRule(group, 'groovy-jsr223'))
                            it.excludeRules.add(newExcludeRule(group, 'groovy-macro'))
                            it.excludeRules.add(newExcludeRule(group, 'groovy-servlet'))
                            it.excludeRules.add(newExcludeRule(group, 'groovy-swing'))
                            it.excludeRules.add(newExcludeRule(group, 'groovy-testng'))
                            it.excludeRules.add(newExcludeRule('org.apache.ant', '*'))
                            it.excludeRules.add(newExcludeRule('commons-cli', '*'))
                            it.excludeRules.add(newExcludeRule('junit', '*'))
                            it.excludeRules.add(newExcludeRule('org.junit.jupiter', '*'))
                            it.excludeRules.add(newExcludeRule('org.junit.platform', '*'))
                        }
                    )
                    if (compareVersions(version, '2.5') >= 0) {
                        pom.apiDependencies.add(
                            newMavenDependency(
                                group,
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
            boolean isGroovy = isGroovyGroup(it.group)
            isGroovy &= it.module == 'groovy' || it.module == 'groovy-all'
            return isGroovy
        }

        // Has only expected dependencies
        assert resolvedModuleComponentIdentifiers.every {
            boolean isExpected = isGroovyGroup(it.group)
            if (isVersionInRange('7', gradleApiVersion, null)) {
                isExpected |= "${it.group}:${it.module}" == 'com.github.javaparser:javaparser-core'
            }
            return isExpected
        }

        // Doesn't have some specific Groovy dependencies
        assert !resolvedModuleComponentIdentifiers.any {
            if (!isGroovyGroup(it.group)) {
                return false
            }

            boolean isNotExpected = it.module == 'groovy-cli-picocli'
            isNotExpected |= it.module == 'groovy-docgenerator'
            isNotExpected |= it.module == 'groovy-groovysh'
            isNotExpected |= it.module == 'groovy-jmx'
            isNotExpected |= it.module == 'groovy-jsr223'
            isNotExpected |= it.module == 'groovy-macro'
            isNotExpected |= it.module == 'groovy-servlet'
            isNotExpected |= it.module == 'groovy-swing'
            isNotExpected |= it.module == 'groovy-testng'
            return isNotExpected
        }

        // Has expected classes
        Set<String> zipsEntryNames = ZipUtils.getZipsEntryNames(configuration)
        assert zipsEntryNames.contains('groovy/lang/Closure.class')
        assert zipsEntryNames.contains('groovy/lang/GString.class')
        assert zipsEntryNames.contains('groovy/json/JsonOutput.class')
        assert zipsEntryNames.contains('groovy/json/JsonSlurper.class')
    }

    private boolean isGroovyGroup(String group) {
        return group == 'org.apache.groovy'
            || group == 'org.codehaus.groovy'
    }

    private String getGroovyGroup(String groovyVersion) {
        if (compareVersions(groovyVersion, '3.9999') < 0) {
            return 'org.codehaus.groovy'
        }

        return 'org.apache.groovy'
    }

}
