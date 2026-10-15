package dev.rook.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadRunnerTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(
                "/",
                exchange -> {
                    hits.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void countsEveryRequestAndComputesThroughput() throws Exception {
        LoadRunner.Result result =
                new LoadRunner()
                        .run(
                                Scenarios.apiRead(base(), "k"),
                                4,
                                Duration.ofMillis(200),
                                Duration.ofMillis(600));

        assertThat(result.requests()).isGreaterThan(10);
        assertThat(result.failures()).isZero();
        assertThat(result.statuses()).containsOnlyKeys("ok-200");
        assertThat(result.statuses().get("ok-200")).isEqualTo(result.requests());
        assertThat(result.requestsPerSecond()).isGreaterThan(0);
        assertThat(result.latency().count()).isEqualTo(result.requests());
        assertThat(result.latency().p50Millis()).isLessThanOrEqualTo(result.latency().p99Millis());
        assertThat(hits.get()).isGreaterThan((int) result.requests());
    }

    @Test
    void unexpectedStatusesAreFailures() throws Exception {
        status = 500;

        LoadRunner.Result result =
                new LoadRunner()
                        .run(
                                Scenarios.apiRead(base(), "k"),
                                2,
                                Duration.ZERO,
                                Duration.ofMillis(300));

        assertThat(result.requests()).isGreaterThan(0);
        assertThat(result.failures()).isEqualTo(result.requests());
        assertThat(result.statuses()).containsOnlyKeys("bad-500");
    }

    @Test
    void connectionErrorsAreCountedNotThrown() throws Exception {
        String dead = base();
        server.stop(0);

        LoadRunner.Result result =
                new LoadRunner()
                        .run(
                                Scenarios.apiRead(dead, "k"),
                                2,
                                Duration.ZERO,
                                Duration.ofMillis(300));

        assertThat(result.failures()).isEqualTo(result.requests()).isGreaterThan(0);
        assertThat(result.statuses().keySet()).allMatch(label -> label.startsWith("error-"));
    }

    @Test
    void rejectsANonPositiveConcurrency() {
        assertThatThrownBy(
                        () ->
                                new LoadRunner()
                                        .run(
                                                Scenarios.apiRead(base(), "k"),
                                                0,
                                                Duration.ZERO,
                                                Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
