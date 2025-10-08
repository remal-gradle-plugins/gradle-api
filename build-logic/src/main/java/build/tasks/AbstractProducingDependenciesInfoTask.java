package build.tasks;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.deleteIfExists;

import build.dto.GradleDependencies;
import build.utils.Json;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class AbstractProducingDependenciesInfoTask extends AbstractGradleFilesConsumerTask {

    protected abstract GradleDependencies createGradleDependencies() throws Exception;


    @OutputFile
    public abstract RegularFileProperty getGradleDependenciesJsonFile();

    {
        getGradleDependenciesJsonFile().convention(getLayout().getBuildDirectory().file(getName() + "/info.json"));
    }


    {
        onlyIf(__ -> {
            getGradleDependenciesJsonFile().finalizeValueOnRead();
            return true;
        });
    }


    @TaskAction
    public final void execute() throws Exception {
        var outputFile = getGradleDependenciesJsonFile().getAsFile().get().toPath();
        deleteIfExists(outputFile);
        createDirectories(outputFile.getParent());

        var result = createGradleDependencies();
        Json.JSON_WRITER.writeValue(outputFile.toFile(), result);
    }

}
