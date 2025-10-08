package build.dto;

import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.LOWER_HYPHEN;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toCollection;
import static lombok.AccessLevel.NONE;

import build.utils.JsonHooks;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Data
public class GradleDependencies implements JsonHooks {

    @JsonProperty(index = 1)
    private final String gradleVersion;

    @JsonProperty(index = 2)
    private final String sourcesArchiveFile;

    @JsonProperty(index = 3)
    private Map<GradleDependencyId, GradleDependencyInfo> dependencies = new LinkedHashMap<>();


    public Set<GradleDependencyId> getAllDependencies(GradleDependencyId id) {
        var dependencies = this.dependencies.get(id);
        if (dependencies == null) {
            throw new IllegalStateException("Not registered dependency: " + id);
        }

        var result = new LinkedHashSet<>(dependencies.getDependencies());
        dependencies.getDependencies().stream()
            .map(this::getAllDependencies)
            .flatMap(Collection::stream)
            .forEach(result::add);
        return unmodifiableSet(result);
    }


    public void cleanup() {
        removeRedundantDependencies();
    }

    private void removeRedundantDependencies() {
        dependencies.forEach((id, info) -> {
            if (info.getDependencies().isEmpty()) {
                return;
            }

            dependencies.forEach((otherId, otherInfo) -> {
                if (id.equals(otherId)) {
                    return;
                }

                if (otherInfo.getDependencies().contains(id)) {
                    otherInfo.getDependencies().removeAll(info.getDependencies());
                }

                var otherHasAllDependencies = otherInfo.getDependencies().containsAll(info.getDependencies());
                if (!otherHasAllDependencies) {
                    return;
                }

                if (info.hasArtifact()) {
                    if (!otherInfo.getDependencies().contains(id)) {
                        return;
                    }
                }

                var newOtherDeps = new LinkedHashSet<GradleDependencyId>();
                for (var otherDep : otherInfo.getDependencies()) {
                    if (info.getDependencies().contains(otherDep)) {
                        newOtherDeps.add(id);
                    } else {
                        newOtherDeps.add(otherDep);
                    }
                }
                otherInfo.setDependencies(newOtherDeps);
            });
        });
    }


    @Getter(NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @JsonIgnore
    private final Map<String, GradleDependencyId> dependencyIdsCache = new LinkedHashMap<>();

    private GradleDependencyId internGradleDependencyId(GradleDependencyId id) {
        var oldId = dependencyIdsCache.putIfAbsent(id.getName(), id);
        if (oldId != null) {
            if (!id.getVersion().isEmpty()) {
                oldId.setVersion(id.getVersion());
            }
            if (!id.getGroup().isEmpty()) {
                oldId.setGroup(id.getGroup());
            }
        }
        return oldId != null ? oldId : id;
    }

    public GradleDependencyId getGradleDependencyIdByPathOrName(String pathOrName) {
        final String fileName;
        if (pathOrName.endsWith(".jar")) {
            fileName = new File(pathOrName).getName();
        } else {
            fileName = pathOrName + "-" + gradleVersion + ".jar";
        }

        var id = GradleDependencyId.fromPath(fileName);
        return internGradleDependencyId(id);
    }

    public GradleDependencyId getGradleDependencyIdByPathOrName(Path path) {
        return getGradleDependencyIdByPathOrName(path.getFileName().toString());
    }

    public GradleDependencyId getGradleDependencyIdByPathOrName(File file) {
        return getGradleDependencyIdByPathOrName(file.getName());
    }

    public GradleDependencyId getGradleDependencyIdByPathOrName(String pathOrName, String version, String group) {
        var id = getGradleDependencyIdByPathOrName(pathOrName);
        id.setVersion(version);
        id.setGroup(group);
        return id;
    }

    public GradleDependencyId getGradleDependencyIdByMethodName(String methodName) {
        var name = LOWER_CAMEL.to(LOWER_HYPHEN, methodName);
        return getGradleDependencyIdByPathOrName(name);
    }

    public void internGradleDependencyIds() {
        var newDeps = new LinkedHashMap<GradleDependencyId, GradleDependencyInfo>();
        dependencies.forEach((id, info) -> {
            id = internGradleDependencyId(id);

            info.setDependencies(info.getDependencies().stream()
                .map(this::internGradleDependencyId)
                .collect(toCollection(LinkedHashSet::new)));

            newDeps.put(id, info);
        });
        dependencies.clear();
        dependencies.putAll(newDeps);
    }

    @Override
    public void beforeSerialization() {
        cleanup();
    }

    @Override
    public void afterDeserialization() {
        internGradleDependencyIds();
    }

}
