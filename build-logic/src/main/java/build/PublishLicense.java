package build;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

public abstract class PublishLicense {

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getName();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getUrl();

}
