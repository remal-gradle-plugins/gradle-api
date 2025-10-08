package build.tasks;

import static org.gradle.api.tasks.PathSensitivity.RELATIVE;

import build.dto.GradleDependencies;
import build.utils.Json;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;

public abstract class AbstractMappingDependenciesInfoTask extends AbstractProducingDependenciesInfoTask {

    protected abstract GradleDependencies mapGradleDependencies(
        GradleDependencies gradleDependencies
    ) throws Exception;


    @InputFile
    @PathSensitive(RELATIVE)
    public abstract RegularFileProperty getGradleDependenciesFile();


    {
        onlyIf(__ -> {
            getGradleDependenciesFile().finalizeValueOnRead();
            return true;
        });
    }


    @Override
    protected final GradleDependencies createGradleDependencies() throws Exception {
        var deps = Json.JSON_READER.readValue(
            getGradleDependenciesFile().get().getAsFile(),
            GradleDependencies.class
        );

        var result = mapGradleDependencies(deps);
        return result;
    }

}
