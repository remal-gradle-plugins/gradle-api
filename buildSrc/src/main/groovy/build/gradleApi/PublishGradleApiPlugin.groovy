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

    private static final Pattern DEPENDENCY_NAME = Pattern.compile(/(.+)-(\d+(?:\.\d+)*)(?:-([^.]+))?\.([^.]+)/)

    private static final Map<String, String> ARTIFACT_ID_GROUP = [
        'ant'               : 'org.apache.ant',
        'ant-launcher'      : 'org.apache.ant',
        'javax.inject'      : 'javax.inject',
        'jsr305'            : 'com.google.code.findbugs',
        'kotlin-stdlib'     : 'org.jetbrains.kotlin',
        'kotlin-stdlib-jdk7': 'org.jetbrains.kotlin',
        'kotlin-stdlib-jdk8': 'org.jetbrains.kotlin',
        'kotlin-reflect'    : 'org.jetbrains.kotlin',
        'slf4j-api'         : 'org.slf4j',
        'jcl-over-slf4j'    : 'org.slf4j',
        'jul-to-slf4j'      : 'org.slf4j',
        'log4j-over-slf4j'  : 'org.slf4j',
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

        List<File> libFiles = this.libFiles
            .findAll { libDependencies.contains(it.name) }

        ClassLoaderFilter classLoaderFilter = this.classLoaderFilter
        for (File libFile : libFiles) {
            List<String> libFileResourceNames = ZipUtils.getZipEntryNames(libFile).findAll {
                (it.endsWith('.class')
                    || it.startsWith('META-INF/services/')
                    || it.startsWith('META-INF/groovy/')
                )
            }
            libFileResourceNames.removeAll { !classLoaderFilter.isResourceAllowed(it) }
            if (!libFileResourceNames.isEmpty()) {
                Matcher matcher = DEPENDENCY_NAME.matcher(libFile.name)
                if (matcher.matches()) {
                    String artifactId = matcher.group(1)
                    String group = ARTIFACT_ID_GROUP[artifactId]
                    if (group == null) {
                        throw new IllegalStateException("Group can't be found for ${libFile.name}")
                    }

                    String version = matcher.group(2)
                    String classifier = matcher.group(3)
                    String type = matcher.group(4)

                    pom.apiDependencies.add(
                        newMavenDependency(
                            group,
                            artifactId,
                            version,
                            classifier,
                            type
                        )
                    )

                } else {
                    throw new IllegalStateException("${libFile.name} doesn't match to $DEPENDENCY_NAME")
                }
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


        // Exclusions:
        classesJar.exclude('org/gradle/internal/impldep/**')
        classesJar.exclude('com/sun/xml/bind/**')

        // Exclude all libs:
        Set<String> libFileEntries = libFiles.stream()
            .flatMap { file -> ZipUtils.getZipEntryNames(file).stream() }
            .collect(Collectors.toSet())
        classesJar.exclude { FileTreeElement element ->
            if (!element.directory) {
                String relativePath = element.relativePath.toString()
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

        // Has slf4j dependency
        assert resolvedModuleComponentIdentifiers.any {
            return "${it.group}:${it.module}" == 'org.slf4j:slf4j-api'
        }

        // Has Kotlin dependencies
        assert resolvedModuleComponentIdentifiers.any {
            return "${it.group}:${it.module}" == 'org.jetbrains.kotlin:kotlin-stdlib'
        }
        if (compareVersions(gradleApiVersion, '6.1') >= 0) {
            assert resolvedModuleComponentIdentifiers.any {
                return "${it.group}:${it.module}" == 'org.jetbrains.kotlin:kotlin-stdlib-jdk7'
            }
            assert resolvedModuleComponentIdentifiers.any {
                return "${it.group}:${it.module}" == 'org.jetbrains.kotlin:kotlin-stdlib-jdk8'
            }
            assert resolvedModuleComponentIdentifiers.any {
                return "${it.group}:${it.module}" == 'org.jetbrains.kotlin:kotlin-reflect'
            }
        }
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
            disallowedClassNames: classLoaders.collect { it.spec?.disallowedClassNames ?: [] }.flatten().unique().sort().toSet(),
            classNames: classLoaders.collect { it.spec?.classNames ?: [] }.flatten().unique().sort().toSet(),
            disallowedPackagePrefixes: classLoaders.collect { it.spec?.disallowedPackagePrefixes ?: [] }.flatten().unique().sort(),
            packagePrefixes: classLoaders.collect { it.spec?.packagePrefixes ?: [] }.flatten().unique().sort(),
            packageNames: classLoaders.collect { it.spec?.packageNames ?: [] }.flatten().unique().sort().toSet(),
            resourceNames: classLoaders.collect { it.spec?.resourceNames ?: [] }.flatten().unique().sort().toSet(),
            resourcePrefixes: classLoaders.collect { it.spec?.resourcePrefixes ?: [] }.flatten().unique().sort(),
        )
    }

}
