package build.utils;

import build.PublishLicense;
import org.gradle.api.Action;
import org.gradle.api.tasks.Nested;

public interface WithPublishLicense {

    @Nested
    PublishLicense getLicense();

    default void license(Action<? super PublishLicense> action) {
        action.execute(getLicense());
    }

}
