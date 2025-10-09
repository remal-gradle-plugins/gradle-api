package build.utils;

import javax.inject.Inject;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;

public abstract class DependenciesInjectable {

    @Inject
    public abstract ConfigurationContainer getConfigurations();

    @Inject
    public abstract DependencyHandler getDependencies();

}
