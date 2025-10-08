package build.utils;

public interface JsonHooks {

    default void beforeSerialization() {
        // do nothing
    }

    default void afterDeserialization() {
        // do nothing
    }

}
