package build.utils;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

public interface WithGradleVersion {

    @Input
    Property<String> getGradleVersion();

}
