package test;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.createTempDirectory;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.write;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import infra.MinTestableGradleVersion;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@MinTestableGradleVersion(
    value = "2.7",
    reason = "GradleRunner <2.7 should be loaded from a Gradle distribution"
)
class GradleRunnerTest {

    final File projectDir;

    public GradleRunnerTest() throws IOException {
        this.projectDir = createTempDirectory(getClass().getSimpleName() + '-').toFile();
    }

    @AfterEach
    void afterEach() {
        try {
            deleteRecursively(projectDir.toPath());
        } catch (IOException e) {
            // do nothing
        }
    }


    @Test
    void helpTask() throws Exception {
        write(
            projectDir.toPath().resolve("settings.gradle"),
            ("rootProject.name = '" + projectDir.getName() + "'").getBytes(UTF_8)
        );

        write(
            projectDir.toPath().resolve("build.gradle"),
            new byte[0]
        );

        Properties properties = new Properties();
        properties.setProperty("org.gradle.logging.stacktrace", "all");
        properties.setProperty("org.gradle.configuration-cache", "false");
        properties.setProperty("org.gradle.caching", "false");
        properties.setProperty("org.gradle.parallel", "false");
        properties.setProperty("org.gradle.daemon.idletimeout", "2500");
        properties.setProperty("org.gradle.ignoreInitScripts", "true");
        try (OutputStream out = newOutputStream(projectDir.toPath().resolve("gradle.properties"))) {
            properties.store(out, null);
        }

        GradleRunner runner = createGradleRunner(projectDir)
            .withArguments("help");
        assertDoesNotThrow(runner::build);
    }


    private static GradleRunner createGradleRunner(File projectDir) {
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .forwardOutput()
            .withGradleVersion(GradleVersion.current().getVersion());
    }

}
