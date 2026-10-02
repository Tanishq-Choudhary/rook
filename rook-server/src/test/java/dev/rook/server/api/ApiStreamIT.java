package dev.rook.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rook.changes.source.WatchedSource;
import dev.rook.changes.store.ChangeIngestion;
import dev.rook.changes.store.SourceRepository;
import dev.rook.control.auth.ApiKeys;
import dev.rook.control.auth.Scope;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.id.Ids;
import dev.rook.core.job.JobState;
import dev.rook.core.json.Json;
import dev.rook.workflow.JobRepository;
import dev.rook.workflow.repo.RepositoryStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@AutoConfigureMetrics
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "rook.role=api",
            "rook.control.rate-burst=500",
            "rook.api.stream-poll=PT0.05S",
            "rook.api.stream-heartbeat=PT0.4S",
            "rook.api.max-streams=3"
        })
@Testcontainers
class ApiStreamIT {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired RepositoryStore repositories;
    @Autowired SourceRepository sources;
    @Autowired ChangeIngestion ingestion;
    @Autowired JobRepository jobs;
    @Autowired ApiKeys keys;
    @Autowired MeterRegistry meters;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Reader> readers = new CopyOnWriteArrayList<>();

    private static int counter;
    private UUID installation;
    private UUID otherInstallation;
    private UUID job;
    private UUID strangerJob;
    private String reader;
    private String stranger;

    private static final class Reader {
        final List<String> lines = new CopyOnWriteArrayList<>();
        volatile int status;
        Socket socket;
        Thread thread;

        void close() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                status = status == 0 ? -1 : status;
            }
        }
    }

    @BeforeEach
    void prepare() {
        int n = ++counter;
        if (sources.find("acme-sdk").isEmpty()) {
            sources.register(
                    new WatchedSource(
                            "acme-sdk",
                            SourceKind.MAVEN,
                            "Acme SDK",
                            "http://upstream.test",
                            null,
                            null,
                            null,
                            null,
                            null,
                            900,
                            true));
        }
        installation = repositories.upsertInstallation(100L + n, "acme" + n).id();
        otherInstallation = repositories.upsertInstallation(200L + n, "other" + n).id();
        UUID repository =
                repositories.upsertRepository(installation, 300L + n, "acme/shop" + n, "main").id();
        UUID foreign =
                repositories
                        .upsertRepository(otherInstallation, 400L + n, "other/app" + n, "main")
                        .id();
        ChangeEvent event =
                ChangeEvent.of(
                        "acme-sdk",
                        "1." + n,
                        "2." + n,
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod("com.acme.Client", "charge", List.of("long")),
                        Map.of(Attr.NAME, "charge"),
                        "charge renamed");
        UUID changeSet =
                ingestion
                        .ingest(
                                new ChangeSet(
                                        Ids.newId(),
                                        "acme-sdk",
                                        SourceKind.MAVEN,
                                        "1." + n,
                                        "2." + n,
                                        List.of(event),
                                        List.of(),
                                        Instant.parse("2026-10-01T10:00:00Z")))
                        .changeSetId();
        job = jobs.create(changeSet, repository, 0, 3).jobId();
        strangerJob = jobs.create(changeSet, foreign, 0, 3).jobId();
        reader = keys.issue(installation, "reader" + n, EnumSet.of(Scope.READ)).key();
        stranger = keys.issue(otherInstallation, "stranger" + n, EnumSet.of(Scope.READ)).key();
    }

    @AfterEach
    void closeStreams() {
        readers.forEach(Reader::close);
        readers.clear();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(meters.get("rook.api.streams.open").gauge().value())
                                        .isEqualTo(0.0));
    }

    private Reader open(String path, String key, String lastEventId) {
        Reader result = new Reader();
        try {
            result.socket = new Socket("localhost", port);
            StringBuilder request = new StringBuilder();
            request.append("GET ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: localhost\r\nAccept: text/event-stream\r\n");
            if (key != null) {
                request.append("Authorization: Bearer ").append(key).append("\r\n");
            }
            if (lastEventId != null) {
                request.append("Last-Event-ID: ").append(lastEventId).append("\r\n");
            }
            request.append("\r\n");
            result.socket
                    .getOutputStream()
                    .write(request.toString().getBytes(StandardCharsets.US_ASCII));
            result.socket.getOutputStream().flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.thread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (BufferedReader in =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            result.socket.getInputStream(),
                                                            StandardCharsets.UTF_8))) {
                                        String first = in.readLine();
                                        result.status =
                                                first == null
                                                        ? -1
                                                        : Integer.parseInt(first.split(" ")[1]);
                                        String line;
                                        while ((line = in.readLine()) != null) {
                                            result.lines.add(line);
                                        }
                                    } catch (IOException | RuntimeException e) {
                                        result.status = result.status == 0 ? -1 : result.status;
                                    }
                                });
        readers.add(result);
        return result;
    }

    private static List<String> dataLines(Reader reader) {
        return reader.lines.stream().filter(line -> line.startsWith("data:")).toList();
    }

    @Test
    void aJobStreamReplaysItsHistoryAndThenFollowsNewEvents() {
        Reader stream = open("/api/v1/jobs/" + job + "/events", reader, null);
        await().atMost(Duration.ofSeconds(10)).until(() -> dataLines(stream).size() >= 1);
        assertThat(dataLines(stream).get(0)).contains("JOB_CREATED");
        assertThat(stream.lines).anyMatch(line -> line.startsWith("id:"));
        assertThat(stream.lines).contains("event:job");
        jobs.recordEvent(job, "STEP_STARTED", JobState.INDEXING, Map.of("worker", "w1"));
        await().atMost(Duration.ofSeconds(10)).until(() -> dataLines(stream).size() >= 2);
        assertThat(Json.tree(dataLines(stream).get(1).substring(5)).get("kind").asString())
                .isEqualTo("STEP_STARTED");
    }

    @Test
    void resumingFromAnEventIdSkipsWhatWasAlreadySeen() {
        jobs.recordEvent(job, "FIRST", JobState.INDEXING, Map.of());
        jobs.recordEvent(job, "SECOND", JobState.INDEXING, Map.of());
        long afterFirst =
                jobs.events(job, 0, 10).stream()
                        .filter(e -> e.kind().equals("FIRST"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        Reader stream = open("/api/v1/jobs/" + job + "/events", reader, Long.toString(afterFirst));
        await().atMost(Duration.ofSeconds(10)).until(() -> !dataLines(stream).isEmpty());
        assertThat(dataLines(stream)).hasSize(1);
        assertThat(dataLines(stream).get(0)).contains("SECOND");
    }

    @Test
    void anIdleStreamKeepsTheConnectionAliveWithComments() {
        Reader stream =
                open("/api/v1/jobs/" + job + "/events", reader, Long.toString(Long.MAX_VALUE / 2));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> stream.lines.stream().anyMatch(l -> l.contains("keep-alive")));
        assertThat(dataLines(stream)).isEmpty();
    }

    @Test
    void theGlobalStreamOnlyCarriesLiveEventsOfTheCallersInstallation() throws Exception {
        Reader stream = open("/api/v1/events", reader, null);
        await().atMost(Duration.ofSeconds(10)).until(() -> stream.status == 200);
        Thread.sleep(300);
        jobs.recordEvent(strangerJob, "SECRET_STEP", JobState.INDEXING, Map.of());
        jobs.recordEvent(job, "VISIBLE_STEP", JobState.INDEXING, Map.of());
        await().atMost(Duration.ofSeconds(10)).until(() -> !dataLines(stream).isEmpty());
        assertThat(dataLines(stream)).hasSize(1);
        assertThat(dataLines(stream).get(0)).contains("VISIBLE_STEP").doesNotContain("SECRET_STEP");
    }

    @Test
    void streamsNeedAKeyAndRespectInstallationBoundaries() throws Exception {
        assertThat(statusOf("/api/v1/jobs/" + job + "/events", null)).isEqualTo(401);
        assertThat(statusOf("/api/v1/jobs/" + job + "/events", stranger)).isEqualTo(404);
        assertThat(statusOf("/api/v1/events?installation=" + installation, stranger))
                .isEqualTo(404);
        assertThat(statusOf("/api/v1/jobs/" + UUID.randomUUID() + "/events", reader))
                .isEqualTo(404);
    }

    @Test
    void aBadResumeIdIsRejected() throws Exception {
        HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/api/v1/events"))
                                .header("Authorization", "Bearer " + reader)
                                .header("Last-Event-ID", "abc")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void thereIsALimitOnConcurrentStreams() throws Exception {
        for (int i = 0; i < 3; i++) {
            Reader stream = open("/api/v1/events", reader, null);
            await().atMost(Duration.ofSeconds(10)).until(() -> stream.status == 200);
        }
        assertThat(statusOf("/api/v1/events", reader)).isEqualTo(429);
        readers.forEach(Reader::close);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf("/api/v1/events", reader)).isEqualTo(200));
    }

    private int statusOf(String path, String key) throws Exception {
        Reader probe = open(path, key, null);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> probe.status != 0);
            return probe.status;
        } finally {
            probe.close();
            readers.remove(probe);
        }
    }
}
