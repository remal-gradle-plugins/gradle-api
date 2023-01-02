package build

import static java.util.Collections.emptySet

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.VersionInfo
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.Versioned
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider

abstract class BaseProjectPlugin implements Plugin<Project> {

    protected Project project;

    protected abstract void applyImpl();


    @Override
    final void apply(Project project) {
        this.project = project;
        applyImpl()
    }


    protected final TaskContainer getTasks() {
        return project.tasks
    }

    protected final void makeTasksDependOnOthers(Task task, Spec<? extends Task> othersSpec) {
        task.dependsOn(tasks.matching { it != task }.matching(othersSpec))
    }

    protected final void makeTasksDependOnOthers(Spec<? extends Task> taskSpec, Spec<? extends Task> othersSpec) {
        tasks.matching(taskSpec).configureEach { makeTasksDependOnOthers(it, othersSpec) }
    }

    protected final void makeTasksDependOnOther(Spec<? extends Task> taskSpec, Task other) {
        tasks.matching(taskSpec).configureEach { it.dependsOn(other) }
    }

    protected final void makeTasksDependOnOther(Spec<? extends Task> taskSpec, TaskProvider<Task> other) {
        tasks.matching(taskSpec).configureEach { it.dependsOn(other) }
    }

    protected final void makeTasksRunAfterOthers(Task task, Spec<? extends Task> othersSpec) {
        task.mustRunAfter(tasks.matching { it != task }.matching(othersSpec))
    }

    protected final void makeTasksRunAfterOthers(Spec<? extends Task> taskSpec, Spec<? extends Task> othersSpec) {
        tasks.matching(taskSpec).configureEach { makeTasksRunAfterOthers(it, othersSpec) }
    }

    protected final void makeTasksRunAfterOther(Spec<? extends Task> taskSpec, Task other) {
        tasks.matching(taskSpec).configureEach { it.mustRunAfter(other) }
    }

    protected final void makeTasksRunAfterOther(Spec<? extends Task> taskSpec, TaskProvider<Task> other) {
        tasks.matching(taskSpec).configureEach { it.mustRunAfter(other) }
    }

    protected final void makeTasksFinalizedByOthers(Task task, Spec<? extends Task> othersSpec) {
        task.finalizedBy(tasks.matching { it != task }.matching(othersSpec))
    }

    protected final void makeTasksFinalizedByOthers(Spec<? extends Task> taskSpec, Spec<? extends Task> othersSpec) {
        tasks.matching(taskSpec).configureEach { makeTasksFinalizedByOthers(it, othersSpec) }
    }

    protected final void makeTasksFinalizedByOther(Spec<? extends Task> taskSpec, Task other) {
        tasks.matching(taskSpec).configureEach { it.finalizedBy(other) }
    }

    protected final void makeTasksFinalizedByOther(Spec<? extends Task> taskSpec, TaskProvider<Task> other) {
        tasks.matching(taskSpec).configureEach { it.finalizedBy(other) }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    protected static void disableTask(Task task) {
        task.enabled = false
        task.onlyIf { false }
        task.dependsOn = emptySet()

        Iterator registeredFileProperties = task.inputs.registeredFileProperties.iterator()
        while (registeredFileProperties.hasNext()) {
            registeredFileProperties.next()
            registeredFileProperties.remove()
        }
    }

    protected static void disableTask(TaskProvider<? extends Task> task) {
        task.configure { disableTask(it) }
    }


    private static final VersionParser VERSION_PARSER = new VersionParser()
    private static final VersionComparator VERSION_COMPARATOR = new DefaultVersionComparator()

    protected final int compareVersions(CharSequence versionString1, CharSequence versionString2) {
        Version version1 = VERSION_PARSER.transform(versionString1.toString())
        Version version2 = VERSION_PARSER.transform(versionString2.toString())
        Versioned versioned1 = new VersionInfo(version1)
        Versioned versioned2 = new VersionInfo(version2)
        return VERSION_COMPARATOR.compare(versioned1, versioned2)
    }

    protected final isVersionInRange(CharSequence minVersion, CharSequence version, CharSequence maxVersion) {
        if (minVersion != null && compareVersions(minVersion, version) > 0) {
            return false
        }
        if (maxVersion != null && compareVersions(version, maxVersion) > 0) {
            return false
        }
        return true
    }


    protected static <T> Spec<T> andSpecs(Spec<? super T>... specs) {
        return new NamedSpec<T>(specs.join(" and ")) {
            @Override
            boolean isSatisfiedBy(T element) {
                if (specs.length == 0) {
                    return false
                }
                for (Spec<? super T> spec : specs) {
                    if (!spec.isSatisfiedBy(element)) {
                        return false
                    }
                }
                return true
            }
        }
    }

    protected static <T> Spec<T> orSpecs(Spec<? super T>... specs) {
        return new NamedSpec<T>(specs.join(" or ")) {
            @Override
            boolean isSatisfiedBy(T element) {
                if (specs.length == 0) {
                    return false
                }
                for (Spec<? super T> spec : specs) {
                    if (spec.isSatisfiedBy(element)) {
                        return true
                    }
                }
                return false
            }
        }
    }

    protected static <T> Spec<T> notSpec(Spec<T> spec) {
        return new NamedSpec<T>("not $spec") {
            @Override
            boolean isSatisfiedBy(T element) {
                return !spec.isSatisfiedBy(element)
            }
        }
    }


    protected static String toJson(Object object) {
        return JsonOutput.prettyPrint(JsonOutput.toJson(object))
    }

}
