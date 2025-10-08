package build.tasks;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.VerificationTask;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.jvm.toolchain.JavaToolchainService;

public abstract class AbstractBuildLogicTask extends DefaultTask {

    {
        setGroup("gradle-api");
        if (this instanceof VerificationTask) {
            setGroup("verification");
        }
    }


    @Inject
    protected abstract ConfigurationContainer getConfigurations();

    @Inject
    protected abstract DependencyHandler getDependencies();

    @Inject
    protected abstract ProjectLayout getLayout();

    @Inject
    protected abstract ProviderFactory getProviders();

    @Inject
    protected abstract JavaToolchainService getJavaToolchainService();

    @Inject
    protected abstract BuildCancellationToken getBuildCancellationToken();

}
