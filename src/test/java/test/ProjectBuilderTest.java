package test;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.io.CleanupMode.NEVER;

import java.io.File;
import java.io.IOException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
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
        Project project = createProject(projectDir);
        assertDoesNotThrow(() -> project.getPluginManager().apply("java"));
    }

    @Test
    void applyJavaPluginAndResolveDependency() {
        Project project = createProject(projectDir);
        project.getPluginManager().apply("java");
        project.getRepositories().mavenCentral();

        Configuration depsConf = project.getConfigurations().getByName("testImplementation");
        depsConf.getDependencies().add(
            project.getDependencies().create("junit:junit:4.13.2")
        );

        Configuration resolvableConf = project.getConfigurations().getByName("testCompileClasspath");
        assertDoesNotThrow(resolvableConf::getFiles);
        assertThat(resolvableConf.getFiles()).isNotEmpty();
    }


    private static Project createProject(File projectDir) {
        return ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .withName(projectDir.getName())
            .build();
    }

}
