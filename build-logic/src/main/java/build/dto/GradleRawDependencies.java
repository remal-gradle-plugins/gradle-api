package build.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class GradleRawDependencies {

    @JsonProperty(index = 1)
    private final String gradleVersion;

    @JsonProperty(index = 2)
    private final String sourcesArchiveFile;

    @JsonProperty(index = 3)
    private Map<String, List<String>> dependencies = new LinkedHashMap<>();

}
