package dev.rook.changes.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SafeHttpFetcherTest {

    private HttpServer server;
    private SafeHttpFetcher fetcher;
    private final FetchPolicy local = FetchPolicy.strict().allowingLocalHttp();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        if (fetcher != null) {
            fetcher.close();
        }
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private SafeHttpFetcher fetcher(FetchPolicy policy) {
        fetcher = new SafeHttpFetcher(policy);
        return fetcher;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Test
    void fetchesABody() {
        server.createContext("/spec", ex -> respond(ex, 200, "hello"));
        FetchResult result = fetcher(local).get(url("/spec"));
        assertThat(result.ok()).isTrue();
        assertThat(result.text()).isEqualTo("hello");
    }

    @Test
    void passesCallerHeadersAndReportsNotModified() {
        AtomicReference<String> seen = new AtomicReference<>();
        server.createContext(
                "/spec",
                ex -> {
                    seen.set(ex.getRequestHeaders().getFirst("If-None-Match"));
                    ex.getResponseHeaders().add("ETag", "\"abc\"");
                    respond(ex, 304, "");
                });
        FetchResult result = fetcher(local).get(url("/spec"), Map.of("If-None-Match", "\"abc\""));
        assertThat(result.notModified()).isTrue();
        assertThat(seen.get()).isEqualTo("\"abc\"");
        assertThat(result.etag()).contains("\"abc\"");
    }

    @Test
    void followsRelativeRedirects() {
        server.createContext(
                "/old",
                ex -> {
                    ex.getResponseHeaders().add("Location", "/new");
                    respond(ex, 302, "");
                });
        server.createContext("/new", ex -> respond(ex, 200, "moved"));
        assertThat(fetcher(local).get(url("/old")).text()).isEqualTo("moved");
    }

    @Test
    void stopsAfterTheRedirectLimit() {
        server.createContext(
                "/loop",
                ex -> {
                    ex.getResponseHeaders().add("Location", "/loop");
                    respond(ex, 302, "");
                });
        assertThatThrownBy(() -> fetcher(local.withMaxRedirects(2)).get(url("/loop")))
                .isInstanceOf(RookException.class)
                .hasMessageContaining("too many redirects");
    }

    @Test
    void refusesRedirectsToDisallowedSchemes() {
        server.createContext(
                "/evil",
                ex -> {
                    ex.getResponseHeaders().add("Location", "ftp://internal.example/secret");
                    respond(ex, 302, "");
                });
        assertThatThrownBy(() -> fetcher(local).get(url("/evil")))
                .isInstanceOf(RookException.class)
                .hasMessageContaining("scheme ftp");
    }

    @Test
    void refusesFileAndOtherSchemesUpFront() {
        SafeHttpFetcher strict = fetcher(FetchPolicy.strict());
        assertThatThrownBy(() -> strict.get(URI.create("file:///etc/passwd")))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(() -> strict.get(URI.create("http://example.com/")))
                .isInstanceOf(RookException.class)
                .hasMessageContaining("scheme http");
    }

    @Test
    void refusesCredentialsInUrls() {
        assertThatThrownBy(() -> fetcher(local).get(URI.create("http://user:pw@127.0.0.1:1/x")))
                .isInstanceOf(RookException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void blocksLoopbackAndMetadataAddressesWhenPrivateAccessIsOff() {
        FetchPolicy publicOnly =
                new FetchPolicy(
                        Set.of("http", "https"),
                        false,
                        1024,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        2);
        SafeHttpFetcher guarded = fetcher(publicOnly);
        for (String target :
                new String[] {
                    "http://127.0.0.1:1/",
                    "http://[::1]:1/",
                    "http://169.254.169.254/latest/meta-data/",
                    "http://localhost:1/",
                    "http://10.0.0.1/",
                    "http://192.168.0.10/"
                }) {
            assertThatThrownBy(() -> guarded.get(URI.create(target)))
                    .as(target)
                    .isInstanceOf(RookException.class)
                    .satisfies(
                            e ->
                                    assertThat(((RookException) e).code())
                                            .isEqualTo(ErrorCode.INVALID));
        }
    }

    @Test
    void rejectsOversizedBodiesAnnouncedByContentLength() {
        server.createContext("/big", ex -> respond(ex, 200, "x".repeat(5000)));
        assertThatThrownBy(() -> fetcher(local.withMaxBytes(1000)).get(url("/big")))
                .isInstanceOf(RookException.class)
                .satisfies(
                        e ->
                                assertThat(((RookException) e).code())
                                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    void rejectsOversizedBodiesWithoutAContentLength() {
        server.createContext(
                "/stream",
                ex -> {
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream out = ex.getResponseBody()) {
                        byte[] chunk = new byte[1024];
                        for (int i = 0; i < 64; i++) {
                            out.write(chunk);
                        }
                    } catch (IOException ignored) {
                        ex.close();
                    }
                });
        assertThatThrownBy(() -> fetcher(local.withMaxBytes(4096)).get(url("/stream")))
                .isInstanceOf(RookException.class)
                .satisfies(
                        e ->
                                assertThat(((RookException) e).code())
                                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    void limitAppliesToDecompressedSize() {
        server.createContext(
                "/bomb",
                ex -> {
                    byte[] zeros = new byte[8 * 1024 * 1024];
                    java.io.ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                    try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                        gzip.write(zeros);
                    }
                    byte[] bytes = compressed.toByteArray();
                    ex.getResponseHeaders().add("Content-Encoding", "gzip");
                    ex.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        assertThatThrownBy(() -> fetcher(local.withMaxBytes(1024 * 1024)).get(url("/bomb")))
                .isInstanceOf(RookException.class)
                .satisfies(
                        e ->
                                assertThat(((RookException) e).code())
                                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    void slowDripResponsesHitTheTotalBudget() {
        server.createContext(
                "/drip",
                ex -> {
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream out = ex.getResponseBody()) {
                        for (int i = 0; i < 100; i++) {
                            out.write('x');
                            out.flush();
                            Thread.sleep(100);
                        }
                    } catch (IOException | InterruptedException ignored) {
                        ex.close();
                    }
                });
        FetchPolicy slow =
                local.withTimeouts(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(600));
        assertThatThrownBy(() -> fetcher(slow).get(url("/drip")))
                .isInstanceOf(RookException.class)
                .hasMessageContaining("time budget");
    }

    @Test
    void silentServersHitTheReadTimeout() {
        server.createContext(
                "/silent",
                ex -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    ex.close();
                });
        FetchPolicy impatient =
                local.withTimeouts(
                        Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofSeconds(5));
        assertThatThrownBy(() -> fetcher(impatient).get(url("/silent")))
                .isInstanceOf(RookException.class)
                .satisfies(
                        e ->
                                assertThat(((RookException) e).code())
                                        .isEqualTo(ErrorCode.UNAVAILABLE));
    }

    @Test
    void serverErrorsAreReturnedToTheCaller() {
        server.createContext("/boom", ex -> respond(ex, 503, "down"));
        FetchResult result = fetcher(local).get(url("/boom"));
        assertThat(result.status()).isEqualTo(503);
        assertThat(result.ok()).isFalse();
    }

    @Test
    void refusedConnectionsBecomeUnavailable() {
        int port = server.getAddress().getPort();
        server.stop(0);
        assertThatThrownBy(() -> fetcher(local).get(URI.create("http://127.0.0.1:" + port + "/")))
                .isInstanceOf(RookException.class)
                .satisfies(
                        e ->
                                assertThat(((RookException) e).code())
                                        .isEqualTo(ErrorCode.UNAVAILABLE));
    }
}
