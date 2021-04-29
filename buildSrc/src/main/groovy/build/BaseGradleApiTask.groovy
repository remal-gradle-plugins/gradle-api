package build

import static java.nio.file.Files.createDirectories

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask

@CompileStatic
abstract class BaseGradleApiTask extends DefaultTask {

    protected static final String TASK_GROUP_NAME = 'gradle-api'

    protected BaseGradleApiTask() {
        group = TASK_GROUP_NAME
    }


    protected URL getResourceUrl(String resourceName) {
        URL url = this.class.getResource(resourceName)
        if (url == null && !resourceName.startsWith('/')) {
            url = this.class.getResource('/' + resourceName)
        }
        if (url == null) {
            throw new IllegalStateException("Resource can't be found for class ${this.class.name}: ${resourceName}")
        }
        return url
    }

    protected String getResourceText(String resourceName) {
        URL resourceUrl = getResourceUrl(resourceName)
        return resourceUrl.getText('UTF-8')
    }

    protected void copyResourceTo(String resourceName, File destination) {
        URL resourceUrl = getResourceUrl(resourceName)

        createDirectories(destination.parentFile.toPath())
        resourceUrl.withInputStream { InputStream inputStream ->
            destination.withOutputStream { OutputStream outputStream ->
                byte[] buffer = new byte[8192]
                int read
                while ((read = inputStream.read(buffer)) >= 0) {
                    outputStream.write(buffer, 0, read)
                }
            }
        }
    }

}
