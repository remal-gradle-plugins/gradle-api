package test;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.io.CleanupMode.NEVER;

import java.io.File;
import java.io.IOException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBuilderTest {

    @TempDir(cleanup = NEVER)
    File projectDir;

    @AfterEach
    void afterEach() {
        try {
            deleteRecursively(projectDir.toPath());
        } catch (IOException e) {
            // do nothing
        }
    }


    @Test
    void applyJavaPlugin() {
        Project project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .withName(projectDir.getName())
            .build();
        assertDoesNotThrow(() -> project.getPluginManager().apply("java"));
    }

}
