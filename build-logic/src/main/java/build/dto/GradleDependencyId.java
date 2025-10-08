package build.dto;

import static lombok.AccessLevel.PRIVATE;
import static lombok.AccessLevel.PROTECTED;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.Splitter;
import java.io.File;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.With;

@Data
@RequiredArgsConstructor(access = PROTECTED)
@EqualsAndHashCode(of = "name")
@With
@AllArgsConstructor(access = PRIVATE)
public class GradleDependencyId implements Comparable<GradleDependencyId> {

    private final String name;

    private String version = "";

    private String group = "";


    @JsonCreator
    public static GradleDependencyId fromString(String str) {
        var parts = Splitter.on(":").splitToList(str);
        var result = new GradleDependencyId(parts.get(1));
        result.setVersion(parts.get(2));
        result.setGroup(parts.get(0));
        return result;
    }


    private static final Pattern FIXED_VERSION = Pattern.compile(
        "^(?<name>jsp(-\\w+)?-2[^-]*)-(?<version>\\d+.*)$"
    );

    private static final Pattern DEFAULT_VERSION = Pattern.compile(
        "^(?<name>.+?)-(?<version>\\d+.*)$"
    );

    protected static GradleDependencyId fromPath(String path) {
        var name = new File(path).getName();
        if (!name.endsWith(".jar")) {
            throw new IllegalArgumentException("Not a JAR file path: " + path);
        }
        name = name.substring(0, name.length() - ".jar".length());

        String version;
        var fixedVersionMatcher = FIXED_VERSION.matcher(name);
        if (fixedVersionMatcher.matches()) {
            name = fixedVersionMatcher.group("name");
            version = fixedVersionMatcher.group("version");

        } else {
            var versionMatcher = DEFAULT_VERSION.matcher(name);
            if (!versionMatcher.matches()) {
                throw new IllegalArgumentException("JAR file without version: " + path);
            }
            name = versionMatcher.group("name");
            version = versionMatcher.group("version");
        }

        var depId = new GradleDependencyId(name);
        depId.setVersion(version);
        return depId;
    }


    @Override
    @JsonValue
    public String toString() {
        return group + ":" + name + ":" + version;
    }

    @Override
    public int compareTo(GradleDependencyId other) {
        return name.compareTo(other.name);
    }

}
