package build.gradleApi

import build.BasePublishPlugin
import build.ClassesJar
import build.ZipUtils
import build.localGroovy.PublishLocalGroovyPlugin
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.stream.Collectors
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.FileTreeElement
import org.gradle.api.publish.maven.internal.dependencies.MavenDependencyInternal
import org.gradle.api.publish.maven.internal.publication.MavenPomInternal

class PublishGradleApiPlugin extends BasePublishPlugin {

    @Override
    protected List<Class<? extends BasePublishPlugin>> getDependencyPluginClasses() {
        return [PublishLocalGroovyPlugin]
    }

    @Override
    protected String getPublicationName() {
        return "gradleApi"
    }

    private static final Pattern KOTLIN_DEPENDENCY_NAME =
        Pattern.compile(/(kotlin-.+?)-(\d+.*)\.([^.]+)/)
    private static final Pattern JSPECIFY_DEPENDENCY_NAME =
        Pattern.compile(/(jspecify)-(\d+(?:\.\d+)*)(?:-([^.]+))?\.([^.]+)/)
    private static final Pattern DEPENDENCY_NAME =
        Pattern.compile(/(.+?)-(\d+(?:\.\d+)*)(?:-([^.]+))?\.([^.]+)/)

    private static final Map<String, String> ARTIFACT_ID_GROUP = [
        'ant': 'org.apache.ant',
        'ant-antlr': 'org.apache.ant',
        'ant-launcher': 'org.apache.ant',
        'ant-junit': 'org.apache.ant',
        'javax.inject': 'javax.inject',
        'jsr305': 'com.google.code.findbugs',
        'slf4j-api': 'org.slf4j',
        'jcl-over-slf4j': 'org.slf4j',
        'jul-to-slf4j': 'org.slf4j',
        'log4j-over-slf4j': 'org.slf4j',
    ]

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    protected ConfigurationResult configurePom(MavenPomInternal pom) {
        List<File> gradleApiFiles = this.gradleApiFiles
        if (gradleApiFiles.isEmpty()) {
            return ConfigurationResult.SKIP
        }

        Set<String> libDependencies = new LinkedHashSet<>()
        for (File gradleApiFile : gradleApiFiles) {
            List<String> classpathResourceNames = ZipUtils.getZipEntryNames(gradleApiFile).stream()
                .filter { !it.contains('/') }
                .filter { it.startsWith('gradle-') && it.endsWith('-classpath.properties') }
                .distinct().collect(Collectors.toList())
            for (String classpathResourceName : classpathResourceNames) {
                Properties classpathProperties = ZipUtils.loadProperties(gradleApiFile, classpathResourceName)
                String runtimeClasspath = classpathProperties.getProperty('runtime', '')
                libDependencies.addAll(
                    runtimeClasspath.split(',')
                        .collect { it.trim() }
                        .findAll { it.length() }
                )
            }
        }


        List<File> libFiles = this.libFiles.findAll { libDependencies.contains(it.name) }
        libFiles.addAll(this.libFiles.findAll { it.name.startsWith('kotlin-') })
        libFiles.unique().sort()

        List<File> localGroovyFiles = gradleApiInfo.localGroovyFiles
            ?.collect { stringToFile(it) }
            ?.findAll { doesPathStartWith(it, gradleHomeDir) }
            ?: []
        libFiles.removeIf {
            boolean isLocalGroovy = localGroovyFiles.contains(it)
            isLocalGroovy |= it.name.startsWith('groovy-')
            return isLocalGroovy
        }

        libFiles.removeIf {
            boolean isKotlinInternals = it.name.startsWith('kotlin-compiler-')
            isKotlinInternals |= it.name.startsWith('kotlin-sam-')
            isKotlinInternals |= it.name.startsWith('kotlin-script-')
            isKotlinInternals |= it.name.startsWith('kotlin-scripting-')
            return isKotlinInternals
        }


        ClassLoaderFilter classLoaderFilter = this.classLoaderFilter
        for (File libFile : libFiles) {
            Set<String> libFileResourceNames = ZipUtils.getZipEntryNames(libFile).findAll {
                boolean isValidResource = it.endsWith('.class')
                isValidResource |= it.startsWith('META-INF/services/')
                isValidResource |= it.startsWith('META-INF/groovy/')
                return isValidResource
            }
            libFileResourceNames.removeAll { !classLoaderFilter.isResourceAllowed(it) }
            while (!libFileResourceNames.isEmpty()) {
                Matcher kotlinMatcher = KOTLIN_DEPENDENCY_NAME.matcher(libFile.name)
                if (kotlinMatcher.matches()) {
                    String group = 'org.jetbrains.kotlin'
                    String artifactId = kotlinMatcher.group(1)
                    String version = kotlinMatcher.group(2)
                    if (isVersionInRange('3.2', gradleApiVersion, '3.2.9999')) {
                        version = version.replaceFirst(/-dev-\d+$/, '')
                    }
                    if (isVersionInRange('3.3', gradleApiVersion, '3.4.9999')) {
                        version = version.replaceFirst(/-M\d+$/, '')
                        if (version.matches(/^\d+\.\d+$/)) {
                            version += '.0'
                        }
                    }
                    String type = kotlinMatcher.group(3)
                    pom.apiDependencies.add(
                        newMavenDependency(
                            group,
                            artifactId,
                            version,
                            null,
                            type
                        )
                    )

                    break
                }


                Matcher jspecifyMatcher = JSPECIFY_DEPENDENCY_NAME.matcher(libFile.name)
                if (jspecifyMatcher.matches()) {
                    String group = 'org.jspecify'
                    String artifactId = jspecifyMatcher.group(1)
                    String version = jspecifyMatcher.group(2)
                    String classifier = jspecifyMatcher.group(3)
                    String type = jspecifyMatcher.group(4)

                    if (classifier == 'no-module-annotation') {
                        classifier = ''
                    }

                    MavenDependencyInternal apiDependency = newMavenDependency(
                        group,
                        artifactId,
                        version,
                        classifier,
                        type
                    )

                    apiDependency.excludeRules.add(newExcludeRule('*', '*'))

                    pom.apiDependencies.add(apiDependency)

                    break
                }


                Matcher dependencyMatcher = DEPENDENCY_NAME.matcher(libFile.name)
                if (dependencyMatcher.matches()) {
                    String artifactId = dependencyMatcher.group(1)
                    String group = ARTIFACT_ID_GROUP[artifactId]
                    if (group == null) {
                        throw new IllegalStateException("Group can't be found for ${libFile.name} (artifactId=${artifactId})")
                    }

                    String version = dependencyMatcher.group(2)
                    String classifier = dependencyMatcher.group(3)
                    String type = dependencyMatcher.group(4)

                    MavenDependencyInternal apiDependency = newMavenDependency(
                        group,
                        artifactId,
                        version,
                        classifier,
                        type
                    )

                    apiDependency.excludeRules.add(newExcludeRule('*', '*'))

                    pom.apiDependencies.add(apiDependency)
                    break
                }


                throw new IllegalStateException("${libFile.name} doesn't match to $DEPENDENCY_NAME")
            }
        }

        return ConfigurationResult.PUBLISH
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    protected ConfigurationResult configureClassesJar(ClassesJar classesJar) {
        List<File> gradleApiFiles = this.gradleApiFiles
        if (gradleApiFiles.isEmpty()) {
            return ConfigurationResult.SKIP
        }

        for (File gradleApiFile : gradleApiFiles) {
            classesJar.from(project.zipTree(gradleApiFile))
        }

        // Exclude all libs:
        Set<String> libFileEntries = libFiles.stream()
            .flatMap { file -> ZipUtils.getZipEntryNames(file).stream() }
            .collect(Collectors.toSet())
        classesJar.exclude { FileTreeElement element ->
            if (!element.directory) {
                String relativePath = element.relativePath.toString()
                if (relativePath.startsWith("net/rubygrapefruit/platform/")) {
                    return false;
                }
                return libFileEntries.contains(relativePath)
            }
            return false
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

        // Has javax.inject dependency
        assert resolvedModuleComponentIdentifiers.any {
            return "${it.group}:${it.module}" == 'javax.inject:javax.inject'
        }

        // Has Slf4j dependency
        assert resolvedModuleComponentIdentifiers.any {
            return "${it.group}:${it.module}" == 'org.slf4j:slf4j-api'
        }

        // Has Kotlin dependencies
        if (compareVersions(gradleApiVersion, '3.2') >= 0) {
            assert resolvedModuleComponentIdentifiers.any {
                return "${it.group}:${it.module}" == 'org.jetbrains.kotlin:kotlin-stdlib'
            }
            assert resolvedModuleComponentIdentifiers.any {
                return "${it.group}:${it.module}" == 'org.jetbrains.kotlin:kotlin-reflect'
            }
        }
        if (isVersionInRange('4.4', gradleApiVersion, '7.9999')) {
            assert resolvedModuleComponentIdentifiers.any {
                boolean isJdk7 = "${it.group}:${it.module}" == 'org.jetbrains.kotlin:kotlin-stdlib-jdk7'
                isJdk7 |= "${it.group}:${it.module}" == 'org.jetbrains.kotlin:kotlin-stdlib-jre7'
                return isJdk7
            }
            assert resolvedModuleComponentIdentifiers.any {
                boolean isJdk8 = "${it.group}:${it.module}" == 'org.jetbrains.kotlin:kotlin-stdlib-jdk8'
                isJdk8 |= "${it.group}:${it.module}" == 'org.jetbrains.kotlin:kotlin-stdlib-jre8'
                return isJdk8
            }
        }

        // Doesn't have junit dependencies:
        assert !resolvedModuleComponentIdentifiers.any {
            return it.group == 'org.junit.jupiter' || it.group == 'junit'
        }

        // Has expected classes
        Set<String> zipsEntryNames = ZipUtils.getZipsEntryNames(configuration)
        assert zipsEntryNames.contains('org/gradle/api/Action.class')
        assert zipsEntryNames.contains('org/gradle/api/DefaultTask.class')
        assert zipsEntryNames.contains('org/gradle/api/JavaVersion.class')
        assert zipsEntryNames.contains('org/gradle/api/Plugin.class')
        assert zipsEntryNames.contains('org/gradle/api/Project.class')
        assert zipsEntryNames.contains('org/gradle/api/Task.class')
        assert zipsEntryNames.contains('org/gradle/api/artifacts/Configuration.class')
        if (compareVersions(gradleApiVersion, '3.3') >= 0) {
            assert zipsEntryNames.contains('org/gradle/api/attributes/Attribute.class')
        }
        if (compareVersions(gradleApiVersion, '3.4') >= 0) {
            assert zipsEntryNames.contains('org/gradle/api/attributes/Usage.class')
        }
        assert zipsEntryNames.contains('org/gradle/api/artifacts/Dependency.class')
        if (compareVersions(gradleApiVersion, '4.7') >= 0) {
            assert zipsEntryNames.contains('org/gradle/api/capabilities/Capability.class')
        }
        assert zipsEntryNames.contains('org/gradle/api/file/CopySpec.class')
        assert zipsEntryNames.contains('org/gradle/api/file/FileTree.class')
        assert zipsEntryNames.contains('org/gradle/api/initialization/Settings.class')
        assert zipsEntryNames.contains('org/gradle/api/invocation/Gradle.class')
        assert zipsEntryNames.contains('org/gradle/api/logging/Logger.class')
        assert zipsEntryNames.contains('org/gradle/api/plugins/ApplicationPlugin.class')
        assert zipsEntryNames.contains('org/gradle/api/plugins/GroovyPlugin.class')
        assert zipsEntryNames.contains('org/gradle/api/plugins/JavaPlugin.class')
        assert zipsEntryNames.contains('org/gradle/api/plugins/quality/CheckstylePlugin.class')
        if (compareVersions(gradleApiVersion, '4.0') >= 0) {
            assert zipsEntryNames.contains('org/gradle/api/provider/Provider.class')
        }
        if (compareVersions(gradleApiVersion, '4.3') >= 0) {
            assert zipsEntryNames.contains('org/gradle/api/provider/Property.class')
        }
        assert zipsEntryNames.contains('org/gradle/api/publish/Publication.class')
        assert zipsEntryNames.contains('org/gradle/api/publish/maven/MavenArtifact.class')
        assert zipsEntryNames.contains('org/gradle/api/publish/maven/MavenPublication.class')
        assert zipsEntryNames.contains('org/gradle/api/publish/maven/plugins/MavenPublishPlugin.class')
        assert zipsEntryNames.contains('org/gradle/api/reporting/Report.class')
        if (compareVersions(gradleApiVersion, '6.1') >= 0) {
            assert zipsEntryNames.contains('org/gradle/api/services/BuildService.class')
        }
        assert zipsEntryNames.contains('org/gradle/api/specs/Spec.class')
        assert zipsEntryNames.contains('org/gradle/api/tasks/Copy.class')
        assert zipsEntryNames.contains('org/gradle/api/tasks/Delete.class')
        assert zipsEntryNames.contains('org/gradle/api/tasks/Exec.class')
        assert zipsEntryNames.contains('org/gradle/api/tasks/compile/AbstractCompile.class')
        assert zipsEntryNames.contains('org/gradle/api/tasks/compile/GroovyCompile.class')
        assert zipsEntryNames.contains('org/gradle/api/tasks/compile/JavaCompile.class')
        assert zipsEntryNames.contains('org/gradle/api/tasks/bundling/Jar.class')
        assert zipsEntryNames.contains('org/gradle/api/tasks/bundling/Zip.class')
        assert zipsEntryNames.contains('org/gradle/api/tasks/testing/Test.class')
        assert zipsEntryNames.contains('net/rubygrapefruit/platform/Native.class')
    }


    @CompileStatic(TypeCheckingMode.SKIP)
    protected final List<File> getGradleApiFiles() {
        def gradleApiInfo = this.gradleApiInfo
        return gradleApiInfo.gradleApiFiles
            ?.collect { stringToFile(it) }
            ?.findAll { it.name.startsWith('gradle-') }
            ?: []
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    protected final List<File> getLibFiles() {
        def gradleApiInfo = this.gradleApiInfo
        List<File> gradleApiLibFiles = gradleApiInfo.gradleApiFiles
            ?.collect { stringToFile(it) }
            ?: []

        def classLoaders = gradleApiInfo.classLoaders ?: []
        List<File> libFiles = classLoaders.collect { it.classpath ?: [] }
            .flatten()
            .collect { stringToFile(it) }

        return (gradleApiLibFiles + libFiles)
            .unique()
            .findAll { doesPathStartWith(it, gradleHomeDir) }
            .findAll { !it.name.startsWith('gradle-') }
            .findAll { !it.name.startsWith('groovy-all-') }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    protected final ClassLoaderFilter getClassLoaderFilter() {
        def gradleApiInfo = this.gradleApiInfo
        def classLoaders = gradleApiInfo.classLoaders ?: []
        return new ClassLoaderFilter(
            disallowedClassNames: classLoaders
                .collect { it.spec?.disallowedClassNames ?: [] }.flatten().unique().sort().toSet(),
            classNames: classLoaders.collect { it.spec?.classNames ?: [] }.flatten().unique().sort().toSet(),
            disallowedPackagePrefixes: classLoaders
                .collect { it.spec?.disallowedPackagePrefixes ?: [] }.flatten().unique().sort(),
            packagePrefixes: classLoaders.collect { it.spec?.packagePrefixes ?: [] }.flatten().unique().sort(),
            packageNames: classLoaders.collect { it.spec?.packageNames ?: [] }.flatten().unique().sort().toSet(),
            resourceNames: classLoaders.collect { it.spec?.resourceNames ?: [] }.flatten().unique().sort().toSet(),
            resourcePrefixes: classLoaders.collect { it.spec?.resourcePrefixes ?: [] }.flatten().unique().sort(),
        )
    }

}
