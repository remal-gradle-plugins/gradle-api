package build.dto;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.SequencedSet;
import lombok.Data;
import lombok.experimental.Tolerate;
import org.jspecify.annotations.Nullable;

@Data
public class GradleDependencyInfo {

    @JsonProperty(index = 1)
    @JsonInclude(NON_DEFAULT)
    private boolean root;

    @JsonProperty(index = 2)
    @JsonInclude(NON_DEFAULT)
    private boolean syntheticGroup;

    @Nullable
    @JsonProperty(index = 3)
    private String path;

    @Nullable
    @JsonProperty(index = 4)
    private GradleDependencyId bom;

    @JsonProperty(index = 5)
    private SequencedSet<GradleDependencyId> dependencies = new LinkedHashSet<>();


    @JsonIgnore
    public boolean hasArtifact() {
        return path != null;
    }


    @JsonIgnore
    @Tolerate
    public void setPath(@Nullable Path path) {
        if (path != null) {
            setPath(path.toString().replace('\\', '/'));
        }
    }

    @JsonIgnore
    @Tolerate
    public void setPath(@Nullable File file) {
        if (file != null) {
            setPath(file.toPath());
        }
    }

}
