package dev.rook.eval.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.rook.agent.tools.ReferenceSearch.Passage;
import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.generate.FixtureGenerator;
import dev.rook.verify.sandbox.LocalProcessRunner;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XerjReferencesIT {

    private static final String BINARY = System.getenv("XERJ_BIN");
    private static final String URL =
            System.getenv().getOrDefault("XERJ_URL", "http://localhost:9200");

    private static boolean nodeAnswers() {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(2)).build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void findsTheChangedMethodInTheNewSdkSource(@TempDir Path temp) {
        assumeTrue(BINARY != null && !BINARY.isBlank(), "XERJ_BIN is not set");
        assumeTrue(nodeAnswers(), "no xerj node at " + URL);
        Fixture fixture =
                FixtureGenerator.generate(temp.resolve("fixtures")).stream()
                        .filter(f -> f.id().equals("parameter-type-changed-blob-field"))
                        .findFirst()
                        .orElseThrow();
        XerjReferences references =
                XerjReferences.index(
                        LocalProcessRunner.create(true),
                        BINARY,
                        URL,
                        "it-" + Long.toString(System.nanoTime(), 36),
                        fixture.sdkAfter(),
                        Duration.ofMinutes(2));

        List<Passage> upload = references.search("upload", 3);
        assertThat(upload).isNotEmpty();
        assertThat(upload.get(0).path()).isEqualTo("blobkit/BlobClient.java");
        assertThat(upload.get(0).text()).contains("upload(Ref ref)");

        List<Passage> ref = references.search("record Ref", 3);
        assertThat(ref.get(0).path()).isEqualTo("blobkit/Ref.java");
        assertThat(ref.get(0).text()).contains("public record Ref(String key)");
    }
}
