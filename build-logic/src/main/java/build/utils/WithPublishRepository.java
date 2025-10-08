package build.utils;

import build.PublishRepository;
import org.gradle.api.Action;
import org.gradle.api.tasks.Nested;

public interface WithPublishRepository {

    @Nested
    PublishRepository getRepository();

    default void repository(Action<? super PublishRepository> action) {
        action.execute(getRepository());
    }

}
