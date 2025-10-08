package test;

import static org.assertj.core.api.Assertions.assertThat;

import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.Test;

class GradleVersionTest {

    @Test
    void currentGradleVersionIsExpected() {
        String expectedGradleVersionString = System.getenv("EXPECTED_GRADLE_VERSION");
        assertThat(expectedGradleVersionString).isNotEmpty();

        GradleVersion expectedGradleVersion = GradleVersion.version(expectedGradleVersionString);

        assertThat(GradleVersion.current()).isEqualTo(expectedGradleVersion);
    }

}
