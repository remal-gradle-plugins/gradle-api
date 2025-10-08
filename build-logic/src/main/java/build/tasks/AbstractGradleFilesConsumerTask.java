package build.tasks;

import static java.nio.file.Files.walk;
import static org.gradle.api.tasks.PathSensitivity.RELATIVE;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.jspecify.annotations.Nullable;

public abstract class AbstractGradleFilesConsumerTask extends AbstractBuildLogicTask {

    @InputDirectory
    @PathSensitive(RELATIVE)
    public abstract DirectoryProperty getGradleFilesDirectory();


    {
        onlyIf(__ -> {
            getGradleFilesDirectory().finalizeValueOnRead();
            return true;
        });
    }


    @Nullable
    @SneakyThrows
    protected final Path getGradleModuleFile(String moduleName) {
        var moduleFileName = Pattern.compile(Pattern.quote(moduleName) + "-\\d.*\\.jar");
        var gradleFilesDir = getGradleFilesDirectory().getAsFile().get().toPath();
        try (var walk = walk(gradleFilesDir)) {
            return walk
                .filter(path -> moduleFileName.matcher(path.getFileName().toString()).matches())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
        }
    }

}
