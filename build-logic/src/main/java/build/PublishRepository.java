package build;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

public interface PublishRepository {

    @Input
    Property<String> getUrl();

    @Input
    Property<String> getUsername();

    @Input
    Property<String> getPassword();

}
