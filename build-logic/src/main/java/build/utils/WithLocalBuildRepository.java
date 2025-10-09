package build.utils;

import org.gradle.api.file.DirectoryProperty;

public interface WithLocalBuildRepository {

    DirectoryProperty getLocalBuildRepository();

}
