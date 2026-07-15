package test;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static java.lang.Boolean.parseBoolean;
import static java.nio.file.Files.createTempDirectory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.File;
import java.io.IOException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.repositories.MavenRepositoryContentDescriptor;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProjectBuilderTest {

    final File projectDir;

    public ProjectBuilderTest() throws IOException {
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
    void createProject() {
        assertDoesNotThrow(() -> createProject(projectDir));
    }

    @Test
    void applyJavaPlugin() {
        Project project = createProject(projectDir);
        assertDoesNotThrow(() -> project.getPluginManager().apply("java"));
    }

    @Test
    void applyJavaPluginAndResolveDependency() {
        GradleVersion baseGradleVersion = GradleVersion.current().getBaseVersion();

        Project project = createProject(projectDir);
        project.getPluginManager().apply("java");
        if (parseBoolean(System.getenv("CI"))) {
            project.getRepositories().maven(repo -> {
                repo.setName("googleMavenCentralMirror");
                repo.setUrl("https://maven-central.storage-download.googleapis.com/maven2/");
                if (GradleVersion.current().getBaseVersion().compareTo(GradleVersion.version("5.1")) >= 0) {
                    repo.mavenContent(MavenRepositoryContentDescriptor::releasesOnly);
                }
            });
        }
        project.getRepositories().mavenCentral();

        Configuration depsConf = project.getConfigurations().getByName(
            baseGradleVersion.compareTo(GradleVersion.version("3.4")) >= 0
                ? "testImplementation"
                : "compile"
        );
        depsConf.getDependencies().add(
            project.getDependencies().create("junit:junit:4.13.2")
        );

        Configuration resolvableConf = project.getConfigurations().getByName(
            baseGradleVersion.compareTo(GradleVersion.version("3.4")) >= 0
                ? "testCompileClasspath"
                : "compile"
        );
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
