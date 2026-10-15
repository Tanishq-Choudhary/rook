package dev.rook.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.rook.core.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class LoadCliTest {

    private HttpServer server;
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(
                "/",
                exchange -> {
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

    private int run(ByteArrayOutputStream captured, String... args) {
        return LoadCli.run(args, new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @Test
    void printsUsageWhenArgumentsAreMissing() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        assertThat(run(captured)).isEqualTo(2);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("usage:");
    }

    @Test
    void aSuccessfulRunWritesTheReportAndExitsCleanly(@TempDir Path directory) throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Path report = directory.resolve("nested").resolve("report.json");

        int code =
                run(
                        captured,
                        "--scenario",
                        "api-read",
                        "--base",
                        base(),
                        "--key",
                        "k",
                        "--concurrency",
                        "2",
                        "--seconds",
                        "1",
                        "--warmup",
                        "0",
                        "--out",
                        report.toString());

        assertThat(code).isZero();
        JsonNode document = Json.tree(Files.readString(report));
        assertThat(document.get("result").get("scenario").asString()).isEqualTo("api-read");
        assertThat(document.get("result").get("requests").asLong()).isGreaterThan(0);
        assertThat(document.get("result").get("failures").asLong()).isZero();
        assertThat(document.get("processors").asInt()).isGreaterThan(0);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("\"scenario\"");
    }

    @Test
    void failedRequestsMakeTheRunExitNonZero() {
        status = 503;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        int code =
                run(
                        captured,
                        "--scenario",
                        "webhook",
                        "--base",
                        base(),
                        "--secret",
                        "a-secret-of-sufficient-length",
                        "--concurrency",
                        "1",
                        "--seconds",
                        "1",
                        "--warmup",
                        "0");

        assertThat(code).isEqualTo(1);
    }

    @Test
    void aMissingKeyOrUnknownScenarioIsAUsageError() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        assertThat(run(captured, "--scenario", "api-read", "--base", base())).isEqualTo(2);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("--key is required");
        assertThat(run(captured, "--scenario", "nope", "--base", base())).isEqualTo(2);
    }
}
