package build.tasks;

import static build.Constants.GRADLE_API_PUBLISH_GROUP;
import static java.lang.Math.pow;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.walk;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.gradle.api.tasks.PathSensitivity.RELATIVE;

import build.utils.WithLocalBuildRepository;
import build.utils.WithPublishRepository;
import com.google.common.net.MediaType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "This task publishes artifacts to a remote repository")
@UntrackedTask(because = "This task publishes artifacts to a remote repository")
public abstract class PublishArtifacts extends AbstractBuildLogicTask
    implements WithLocalBuildRepository, WithPublishRepository {

    private static final int MAX_UPLOAD_ATTEMPTS = 5;
    private static final Duration SLEEP_BETWEEN_UPLOAD_ATTEMPTS = Duration.ofSeconds(1);

    private static final Pattern TEXT_CONTENT_TYPE = Pattern.compile("\\b(text|xml|json|yaml)\\b", CASE_INSENSITIVE);


    {
        getOutputs().doNotCacheIf("This task publishes artifacts to a remote repository", _ -> true);
        getOutputs().upToDateWhen(_ -> false);
    }


    @InputDirectory
    @PathSensitive(RELATIVE)
    @Override
    public abstract DirectoryProperty getLocalBuildRepository();


    {
        onlyIf(__ -> {
            getLocalBuildRepository().finalizeValueOnRead();
            return true;
        });
    }


    @TaskAction
    public void execute() throws Exception {
        var basePath = getLocalBuildRepository().get().getAsFile().toPath();
        try (var walk = walk(basePath)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                var relativePath = basePath.relativize(file).toString().replace('\\', '/');
                var expectedRelativePathPrefix = GRADLE_API_PUBLISH_GROUP.replace('.', '/') + '/';
                if (!relativePath.startsWith(expectedRelativePathPrefix)) {
                    return; // skip non-artifact files
                }

                put(relativePath, file);
            });
        }
    }

    @SneakyThrows
    @SuppressWarnings("BusyWait")
    private void put(String relativePath, Path file) {
        while (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        var baseUri = getRepository().getUrl().get();
        while (baseUri.endsWith("/")) {
            baseUri = baseUri.substring(0, baseUri.length() - 1);
        }

        var uri = URI.create(baseUri + '/' + relativePath);
        getLogger().lifecycle("Uploading {}", uri);

        var auth = Base64.getEncoder().encodeToString(format(
            "%s:%s",
            getRepository().getUsername().get(),
            getRepository().getPassword().get()
        ).getBytes(UTF_8));

        var request = HttpRequest.newBuilder(uri)
            .header("Authorization", "Basic " + auth)
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofFile(file))
            .build();

        try (var httpClient = HttpClient.newHttpClient()) {
            for (var attempt = 1; ; attempt++) {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                var statusCode = response.statusCode();
                if (statusCode < 400) {
                    break;
                }

                var isRetryableStatusCode = statusCode >= 500
                    || statusCode == 408
                    || statusCode == 409
                    || statusCode == 425
                    || statusCode == 423
                    || statusCode == 429;
                if (attempt < MAX_UPLOAD_ATTEMPTS && isRetryableStatusCode) {
                    var sleepMillis = toIntExact((long) (
                        SLEEP_BETWEEN_UPLOAD_ATTEMPTS.toMillis() * pow(2, attempt - 1)
                    ));
                    getLogger().warn(
                        "Could not {} `{}`. Received status code {} from server. Will retry in {}ms.",
                        request.method(),
                        request.uri(),
                        statusCode,
                        sleepMillis
                    );
                    Thread.sleep(sleepMillis);
                    continue;
                }

                var responseBytes = response.body();

                var responseBodyString = "";
                var mediaType = response.headers().firstValue("Content-Type")
                    .map(MediaType::parse)
                    .orElse(null);
                if (mediaType != null) {
                    var isText = TEXT_CONTENT_TYPE.matcher(mediaType.withoutParameters().toString()).find();
                    if (isText) {
                        var charset = mediaType.charset().or(UTF_8);
                        responseBodyString = new String(responseBytes, charset);
                    } else if (responseBytes.length > 0) {
                        responseBodyString = format("<non-textual content of %d bytes>", responseBytes.length);
                    }
                }

                throw new IllegalStateException(format(
                    "Could not %s `%s`. Received status code %d from server: %s",
                    request.method(),
                    request.uri(),
                    statusCode,
                    responseBodyString
                ));
            }
        }
    }

}
