package build;

import build.utils.WithPublishLicense;
import javax.inject.Inject;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Property;
import org.gradle.util.GradleVersion;

public abstract class BuildLogicExtension implements WithPublishLicense {

    public abstract Property<String> getGradleVersion();

    {
        getGradleVersion().convention(GradleVersion.current().getVersion());
    }


    public abstract DirectoryProperty getLocalMavenRepository();

    {
        getLocalMavenRepository().convention(getLayout().getBuildDirectory().dir("m2"));
    }


    @Inject
    protected abstract ProjectLayout getLayout();

}
