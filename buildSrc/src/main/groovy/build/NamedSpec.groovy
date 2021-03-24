package build

import org.gradle.api.specs.Spec

abstract class NamedSpec<T> implements Spec<T> {

    private final String name

    NamedSpec(CharSequence name) {
        this.name = name.toString()
    }

    @Override
    String toString() {
        return name
    }

}
