package build.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.File;
import java.nio.file.Path;
import lombok.Data;
import lombok.experimental.Tolerate;
import org.jspecify.annotations.Nullable;

@Data
public class GradlePublishedDependencyInfo {

    @JsonProperty(index = 1)
    private final String pomFilePath;

    @Nullable
    @JsonProperty(index = 2)
    private String jarFilePath;

    @Nullable
    @JsonProperty(index = 3)
    private String sourcesJarFilePath;


    @JsonIgnore
    @Tolerate
    public GradlePublishedDependencyInfo(Path pomFilePath) {
        this(pomFilePath.toString().replace('\\', '/'));
    }

    @JsonIgnore
    @Tolerate
    public GradlePublishedDependencyInfo(File pomFile) {
        this(pomFile.toPath());
    }


    @JsonIgnore
    @Tolerate
    public void setJarFilePath(@Nullable Path jarFilePath) {
        if (jarFilePath != null) {
            setJarFilePath(jarFilePath.toString().replace('\\', '/'));
        }
    }

    @JsonIgnore
    @Tolerate
    public void setJarFilePath(@Nullable File jarFile) {
        if (jarFile != null) {
            setJarFilePath(jarFile.toPath());
        }
    }


    @JsonIgnore
    @Tolerate
    public void setSourcesJarFilePath(@Nullable Path sourcesJarFilePath) {
        if (sourcesJarFilePath != null) {
            setSourcesJarFilePath(sourcesJarFilePath.toString().replace('\\', '/'));
        }
    }

    @JsonIgnore
    @Tolerate
    public void setSourcesJarFilePath(@Nullable File sourcesJarFile) {
        if (sourcesJarFile != null) {
            setSourcesJarFilePath(sourcesJarFile.toPath());
        }
    }

}
