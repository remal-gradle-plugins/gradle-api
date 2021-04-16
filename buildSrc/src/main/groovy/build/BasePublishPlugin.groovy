package build

import static java.util.Collections.emptyList

import build.collect.CollectGradleApiInfo
import build.collect.CollectGradleApiPlugin
import groovy.io.FileType
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import java.util.regex.Pattern
import java.util.stream.Collectors
import javax.annotation.Nullable
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.artifacts.ArtifactRepositoryContainer
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyArtifact
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.internal.artifacts.DefaultExcludeRule
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyArtifact
import org.gradle.api.publish.PublicationContainer
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.internal.dependencies.DefaultMavenDependency
import org.gradle.api.publish.maven.internal.dependencies.MavenDependencyInternal
import org.gradle.api.publish.maven.internal.publication.MavenPomInternal
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask

abstract class BasePublishPlugin extends BaseProjectPlugin {

    protected List<Class<? extends BasePublishPlugin>> getDependencyPluginClasses() {
        return []
    }

    abstract protected String getPublicationName()

    protected String getGroupId() {
        return 'name.remal.gradle-api'
    }

    protected ConfigurationResult configurePom(MavenPomInternal pom) {
        return ConfigurationResult.PUBLISH
    }

    protected ConfigurationResult configureClassesJar(ClassesJar classesJar) {
        return ConfigurationResult.SKIP
    }

    protected ConfigurationResult configureSourceJar(SourcesJar sourcesJar) {
        File srcDir = project.file("${gradleHomeDir}/src")
        if (!srcDir.directory) {
            return ConfigurationResult.SKIP
        }

        srcDir.eachFile(FileType.DIRECTORIES) { dir ->
            sourcesJar.from(dir)
        }

        return ConfigurationResult.PUBLISH
    }

    protected enum ConfigurationResult {
        PUBLISH,
        SKIP,
    }

    abstract protected void validatePublished(Configuration configuration)


    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    protected final void applyImpl() {
        project.plugins.apply(CollectGradleApiPlugin)
        project.plugins.apply(MavenPublishPlugin)
        project.plugins.apply(PublishTasksOrderingPlugin)

        List<BasePublishPlugin> dependencyPlugins = getDependencyPluginClasses().collect {
            return project.plugins.apply(it)
        }
        List<MavenPublication> dependencyPublications = dependencyPlugins.collect { dependency ->
            return publications.withType(MavenPublication).named(dependency.getPublicationName()).get()
        }

        boolean hasMavenLocalRepository = project.repositories
            .any { it.name == ArtifactRepositoryContainer.DEFAULT_MAVEN_LOCAL_REPO_NAME }
        if (!hasMavenLocalRepository) {
            project.repositories.mavenLocal()
        }

        boolean hasMavenCentralRepository = project.repositories
            .any { it.name == ArtifactRepositoryContainer.DEFAULT_MAVEN_CENTRAL_REPO_NAME }
        if (!hasMavenCentralRepository) {
            project.repositories.mavenCentral()
        }

        publications.register(publicationName, MavenPublication) { MavenPublication publication ->
            publication.groupId = this.groupId
            publication.artifactId = publicationName.replaceAll(/([A-Z])/, '-$1').toLowerCase()

            ClassesJar classesJar = tasks.create(getGenerateClassesJarTaskName(publication), ClassesJar)
            MavenArtifact classesJarArtifact = publication.artifact(classesJar)

            SourcesJar sourcesJar = tasks.create(getGenerateSourcesJarTaskName(publication), SourcesJar, classesJar)
            MavenArtifact sourcesJarArtifact = publication.artifact(sourcesJar)

            tasks.withType(AbstractArchiveTask)
                .matching(isGenerateTaskOf(publication))
                .configureEach { AbstractArchiveTask task ->
                    dependsOn(collectGradleApiInfoTask)

                    task.archiveBaseName.set(publication.artifactId)
                    task.archiveVersion.set(project.provider { publication.version })
                }

            tasks.withType(GenerateMavenPom).named(getGeneratePomTaskName(publication)) {
                group = BaseGradleApiTask.TASK_GROUP_NAME

                dependsOn(
                    dependencyPublications.collect { dependencyPublication ->
                        return getGeneratePomTaskName(dependencyPublication)
                    }
                )

                CollectGradleApiInfo collectInfo = collectGradleApiInfoTask
                dependsOn(collectInfo)
                onlyIf {
                    inputs.file(collectInfo.infoJsonFile).withPropertyName('infoJsonFile')
                    return true
                }

                onlyIf {
                    publication.version = this.gradleApiVersion

                    publication.pom { MavenPomInternal pom ->
                        List<MavenDependencyInternal> dependencyApiDependencies = dependencyPublications.collect { dependencyPublication ->
                            newMavenDependency(
                                dependencyPublication.groupId,
                                dependencyPublication.artifactId,
                                dependencyPublication.version
                            )
                        }

                        if (configurePom(pom) != ConfigurationResult.PUBLISH) {
                            disablePublication(publication)
                            return
                        }

                        List<MavenDependencyInternal> apiDependencies = new ArrayList<>(pom.apiDependencies)
                        apiDependencies.sort { dep1, dep2 -> "${dep1.groupId}:${dep1.artifactId}" <=> "${dep2.groupId}:${dep2.artifactId}" }
                        pom.apiDependencies.clear()
                        pom.apiDependencies.addAll(dependencyApiDependencies)
                        pom.apiDependencies.addAll(apiDependencies)

                        if (configureClassesJar(classesJar) != ConfigurationResult.PUBLISH) {
                            disableTask(classesJar)
                            publication.getArtifacts().remove(classesJarArtifact)
                            disableTask(sourcesJar)
                            publication.getArtifacts().remove(sourcesJarArtifact)

                        } else if (configureSourceJar(sourcesJar) != ConfigurationResult.PUBLISH) {
                            disableTask(sourcesJar)
                            publication.getArtifacts().remove(sourcesJarArtifact)
                        }

                        if (publication.getArtifacts().isEmpty()) {
                            pom.packaging = 'pom'
                        }
                    }

                    return true
                }
            }

            makeTasksDependOnOthers(
                andSpecs(isGenerateTaskOf(publication), notSpec(isGeneratePomTaskOf(publication))),
                isGeneratePomTaskOf(publication)
            )

            makeTasksDependOnOthers(isPublishTaskOf(publication), isGeneratePomTaskOf(publication))
            makeTasksRunAfterOthers(isPublishTaskOf(publication), isGenerateTaskOf(publication))


            TaskProvider validateTask = tasks.register(getValidateTaskName(publication)) {
                group = 'gradle-api'

                dependsOn(
                    dependencyPublications.collect { dependencyPublication ->
                        return getValidateTaskName(dependencyPublication)
                    }
                )

                dependsOn(tasks.matching(isPublishToMavenLocalTaskOf(publication)))

                doFirst {
                    Dependency dependency = project.dependencies.create(
                        "${publication.groupId}:${publication.artifactId}:${publication.version}"
                    )
                    Configuration configuration = project.configurations.detachedConfiguration(dependency)
                    validatePublished(configuration)
                    didWork = true
                }
            }

            makeTasksFinalizedByOther(isPublishToMavenLocalTaskOf(publication), validateTask)

            makeTasksDependOnOther(
                andSpecs(isPublishTaskOf(publication), notSpec(isPublishToMavenLocalTaskOf(publication))),
                validateTask
            )
        }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    protected static class PublishTasksOrderingPlugin extends BaseProjectPlugin {
        @Override
        protected void applyImpl() {
            PublishingExtension publishing = project.publishing
            PublicationContainer publications = publishing.publications
            publications.configureEach { publication ->
                tasks.matching(isPublishTaskOf(publication)).configureEach { Task task ->
                    if (!isPublishToMavenLocalTaskOf(task, publication)) {
                        task.mustRunAfter(
                            project.provider {
                                publications.stream()
                                    .filter { it.name != publication.name }
                                    .flatMap { pub ->
                                        tasks.stream()
                                            .filter { isPublishToMavenLocalTaskOf(it, pub) }
                                    }
                                    .collect(Collectors.toList())
                            }
                        )
                    }
                }
            }
        }
    }


    protected final CollectGradleApiInfo getCollectGradleApiInfoTask() {
        return tasks
            .withType(CollectGradleApiInfo)
            .named(CollectGradleApiPlugin.COLLECT_GRADLE_API_INFO_TASK_NAME)
            .get()
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    protected final PublicationContainer getPublications() {
        PublishingExtension publishing = project.publishing
        return publishing.publications
    }


    protected static String getGeneratePomTaskName(MavenPublication publication) {
        return "generatePomFileFor${publication.name.capitalize()}Publication"
    }

    protected static Spec<Task> isGeneratePomTaskOf(MavenPublication publication) {
        String taskName = getGeneratePomTaskName(publication)
        return new NamedSpec<Task>("isGeneratePomTaskOf(${publication.name})") {
            @Override
            boolean isSatisfiedBy(Task task) {
                return task.name == taskName
            }
        }
    }

    protected static String getGenerateClassesJarTaskName(MavenPublication publication) {
        return "generateClassesJarFor${publication.name.capitalize()}Publication"
    }

    protected static Spec<Task> isGenerateClassesJarTaskOf(MavenPublication publication) {
        String taskName = getGenerateClassesJarTaskName(publication)
        return new NamedSpec<Task>("isGenerateClassesJarTaskOf(${publication.name})") {
            @Override
            boolean isSatisfiedBy(Task task) {
                return task.name == taskName
            }
        }
    }

    protected static String getGenerateSourcesJarTaskName(MavenPublication publication) {
        return "generateSourcesJarFor${publication.name.capitalize()}Publication"
    }

    protected static Spec<Task> isGenerateSourcesJarTaskOf(MavenPublication publication) {
        String taskName = getGenerateSourcesJarTaskName(publication)
        return new NamedSpec<Task>("isGenerateSourcesJarTaskOf(${publication.name})") {
            @Override
            boolean isSatisfiedBy(Task task) {
                return task.name == taskName
            }
        }
    }

    protected static Spec<Task> isGenerateTaskOf(MavenPublication publication) {
        Pattern pattern = Pattern.compile(
            /^generate[A-Z].*${Pattern.quote(publication.name.capitalize())}Publication$/
        )
        return new NamedSpec<Task>("isGenerateTaskOf(${publication.name})") {
            @Override
            boolean isSatisfiedBy(Task task) {
                return task.name.matches(pattern)
            }
        }
    }

    protected static Spec<Task> isPublishToMavenLocalTaskOf(MavenPublication publication) {
        String repoName = ArtifactRepositoryContainer.DEFAULT_MAVEN_LOCAL_REPO_NAME
        String taskName = "publish${publication.name.capitalize()}PublicationTo${repoName.capitalize()}"
        return new NamedSpec<Task>("isPublishToMavenLocalTaskOf(${publication.name})") {
            @Override
            boolean isSatisfiedBy(Task task) {
                return task.name == taskName
            }
        }
    }

    protected static boolean isPublishToMavenLocalTaskOf(Task task, MavenPublication publication) {
        return isPublishToMavenLocalTaskOf(publication).isSatisfiedBy(task)
    }

    protected static Spec<Task> isPublishTaskOf(MavenPublication publication) {
        Pattern pattern = Pattern.compile(
            /^publish${Pattern.quote(publication.name.capitalize())}PublicationTo.*$/
        )
        return new NamedSpec<Task>("isPublishTaskOf(${publication.name})") {
            @Override
            boolean isSatisfiedBy(Task task) {
                return task.name.matches(pattern)
            }
        }
    }

    protected static Spec<Task> isTaskOf(MavenPublication publication) {
        return orSpecs(
            isGenerateTaskOf(publication),
            isPublishTaskOf(publication),
        )
    }

    protected static String getValidateTaskName(MavenPublication publication) {
        return "validate${publication.name.capitalize()}Publication"
    }

    protected static Spec<Task> isValidateTaskOf(MavenPublication publication) {
        String taskName = getValidateTaskName(publication)
        return new NamedSpec<Task>("isValidateTaskOf(${publication.name})") {
            @Override
            boolean isSatisfiedBy(Task task) {
                return task.name == taskName
            }
        }
    }

    protected final void disablePublication(MavenPublication publication) {
        publication.artifacts = emptyList()
        tasks.matching(isTaskOf(publication)).configureEach { disableTask(it) }
    }


    private final Closure<Object> gradleApiInfoParser = {
        CollectGradleApiInfo collectInfo = collectGradleApiInfoTask
        if (!collectInfo.didWork
            && !collectInfo.state.outcome.upToDate
        ) {
            throw new IllegalStateException("Task wasn't executed: ${collectInfo.path}")
        }

        File infoJsonFile = collectInfo.infoJsonFile

        def content = new JsonSlurper().parse(infoJsonFile)
        return content
    }.memoize()

    protected final Object getGradleApiInfo() {
        return gradleApiInfoParser.call()
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    protected final File getGradleHomeDir() {
        def gradleApiInfo = this.gradleApiInfo
        String path = gradleApiInfo.gradleHomeDir
        if (path == null) {
            throw new IllegalStateException(
                "Info JSON file doesn't have 'gradleHomeDir' property: ${collectGradleApiInfoTask.infoJsonFile}"
            )
        }
        return stringToFile(path)
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    protected final String getGradleApiVersion() {
        def gradleApiInfo = this.gradleApiInfo
        String version = gradleApiInfo.version
        if (version == null) {
            throw new IllegalStateException(
                "Info JSON file doesn't have 'version' property: ${collectGradleApiInfoTask.infoJsonFile}"
            )
        }
        return version
    }


    protected static File stringToFile(String string) {
        if (string == null) {
            return null
        } else if (string.startsWith('file:')) {
            URI uri = new URI(string)
            return new File(uri)
        } else {
            return new File(string)
        }
    }

    protected static boolean doesPathStartWith(File file, File prefix) {
        if (file == null || prefix == null) {
            return false
        }
        return file.toPath().startsWith(prefix.toPath())
    }


    protected static MavenDependencyInternal newMavenDependency(
        String group,
        String artifactId,
        String version,
        @Nullable String classifier,
        @Nullable String type,
        @Nullable Action<? extends MavenDependencyInternal> configurer
    ) {
        if (type == 'jar') {
            type = null
        }

        MavenDependencyInternal dependency = new DefaultMavenDependency(group, artifactId, version, type)

        if (classifier != null || type != null) {
            dependency.artifacts.add(
                new DefaultDependencyArtifact(
                    artifactId,
                    type,
                    type ?: 'jar',
                    classifier,
                    null
                )
            )
        }

        if (configurer != null) {
            configurer.execute(dependency)
        }

        return dependency
    }

    protected static MavenDependencyInternal newMavenDependency(
        String group,
        String artifactId,
        String version,
        @Nullable Action<? extends MavenDependencyInternal> configurer
    ) {
        return newMavenDependency(group, artifactId, version, null, null, configurer)
    }

    protected static MavenDependencyInternal newMavenDependency(
        String group,
        String artifactId,
        String version,
        @Nullable String classifier,
        @Nullable String type
    ) {
        return newMavenDependency(group, artifactId, version, classifier, type, null)
    }

    protected static MavenDependencyInternal newMavenDependency(String group, String artifactId, String version) {
        return newMavenDependency(group, artifactId, version, null, null, null)
    }

    protected static MavenDependencyInternal newMavenDependency(
        ModuleDependency dependency,
        @Nullable Action<? extends MavenDependencyInternal> configurer
    ) {
        DependencyArtifact artifact = dependency.artifacts.find()
        String classifier = artifact?.classifier
        String type = artifact?.type
        return newMavenDependency(dependency.group, dependency.name, dependency.version, classifier, type, configurer)
    }

    protected static MavenDependencyInternal newMavenDependency(ModuleDependency dependency) {
        return newMavenDependency(dependency, null)
    }

    protected final ExcludeRule newExcludeRule(String group, String artifactId) {
        return new DefaultExcludeRule(group, artifactId)
    }

}
