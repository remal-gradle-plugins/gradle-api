package build;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

public interface PublishLicense {

    @Input
    @org.gradle.api.tasks.Optional
    Property<String> getName();

    @Input
    @org.gradle.api.tasks.Optional
    Property<String> getUrl();

}
