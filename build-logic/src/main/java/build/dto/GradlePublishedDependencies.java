package build.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
public class GradlePublishedDependencies {

    @JsonProperty(index = 1)
    private final String gradleVersion;

    @JsonProperty(index = 2)
    private Map<GradleDependencyId, GradlePublishedDependencyInfo> dependencies = new LinkedHashMap<>();

}
