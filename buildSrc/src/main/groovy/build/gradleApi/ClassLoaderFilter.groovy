package build.gradleApi

import groovy.transform.Immutable
import java.util.regex.Pattern

@Immutable
class ClassLoaderFilter {

    Set<String> disallowedClassNames
    Set<String> classNames
    List<String> disallowedPackagePrefixes
    List<String> packagePrefixes
    Set<String> packageNames
    Set<String> resourceNames
    List<String> resourcePrefixes


    private static final String DEFAULT_PACKAGE = 'DEFAULT'

    boolean isClassAllowed(String className) {
        if (disallowedClassNames.contains(className)) {
            return false;
        }

        if (classNames.contains(className)) {
            return true;
        }

        if (disallowedPackagePrefixes.any { className.startsWith(it) }) {
            return false;
        }

        return (packagePrefixes.any { className.startsWith(it) }
            || (packagePrefixes.contains(DEFAULT_PACKAGE + '.') && isInDefaultPackage(className))
        )
    }

    private boolean isInDefaultPackage(String className) {
        return !className.contains('.')
    }

    boolean isPackageAllowed(String packageName) {
        if (disallowedPackagePrefixes.any { packageName.startsWith(it) }) {
            return false;
        }

        return (packageNames.contains(packageName)
            || packagePrefixes.any { packageName.startsWith(it) }
        )
    }

    private static final Pattern MULTI_RELEASE_RESOURCE_PREFIX = Pattern.compile(/^META-INF\/versions\/\d+\//)

    boolean isResourceAllowed(String resourceName) {
        resourceName = resourceName.replaceFirst(MULTI_RELEASE_RESOURCE_PREFIX, '')

        if (resourceName == 'module-info.class') {
            return false

        } else if (resourceName == 'package-info.class') {
            return isPackageAllowed('')

        } else if (resourceName.endsWith('/package-info.class')) {
            String packageName = resourceName.substring(0, resourceName.lastIndexOf('/')).replace('/', '.')
            return isPackageAllowed(packageName)

        } else if (resourceName.endsWith('.class')) {
            String className = resourceName.substring(0, resourceName.lastIndexOf('.')).replace('/', '.')
            return isClassAllowed(className)
        }

        return (resourceNames.contains(resourceName)
            || resourcePrefixes.any { resourceName.startsWith(it) }
        )
    }

}
