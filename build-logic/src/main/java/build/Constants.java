package build;

import com.google.common.collect.ImmutableSortedMap;
import java.util.Map;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.util.GradleVersion;

public interface Constants {

    String GRADLE_API_PUBLISH_GROUP = "name.remal.gradle-api";

    String GRADLE_API_BOM_NAME = "gradle-api-bom";


    // TODO: generate
    Map<GradleVersion, JavaLanguageVersion> MIN_GRADLE_VERSION_TO_JAVA_VERSION =
        ImmutableSortedMap.<GradleVersion, JavaLanguageVersion>reverseOrder()
            .put(GradleVersion.version("9.0"), JavaLanguageVersion.of(17))
            .build();

    // TODO: generate
    JavaLanguageVersion FALLBACK_JAVA_VERSION = JavaLanguageVersion.of(8);

}
