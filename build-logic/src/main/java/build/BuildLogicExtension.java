package build;

import build.utils.WithGradleVersion;
import build.utils.WithLocalBuildRepository;
import build.utils.WithPublishLicense;
import build.utils.WithPublishRepository;
import javax.inject.Inject;
import org.gradle.api.file.ProjectLayout;
import org.gradle.util.GradleVersion;

public abstract class BuildLogicExtension
    implements WithGradleVersion, WithPublishLicense, WithLocalBuildRepository, WithPublishRepository {

    {
        getGradleVersion().convention(GradleVersion.current().getVersion());
    }


    {
        getLocalBuildRepository().convention(getLayout().getBuildDirectory().dir("m2"));
    }


    @Inject
    protected abstract ProjectLayout getLayout();

}
