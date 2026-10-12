package dev.rook.server.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.changes.source.WatchedSource;
import dev.rook.changes.store.ChangeIngestion;
import dev.rook.changes.store.SourceRepository;
import dev.rook.control.audit.AuditLog;
import dev.rook.control.auth.ApiKeys;
import dev.rook.control.auth.Scope;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import dev.rook.workflow.JobRepository;
import dev.rook.workflow.repo.RepositoryStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

@AutoConfigureMetrics
@ActiveProfiles("chaos")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "rook.role=api",
            "rook.control.bootstrap-key=bootstrap-key-with-more-than-thirty-two-chars",
            "rook.control.rate-burst=500"
        })
@Testcontainers
class ChaosIT {

    private static final String ADMIN = "bootstrap-key-with-more-than-thirty-two-chars";

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired RepositoryStore repositories;
    @Autowired SourceRepository sources;
    @Autowired ChangeIngestion ingestion;
    @Autowired JobRepository jobs;
    @Autowired ApiKeys keys;
    @Autowired AuditLog audit;
    @Autowired JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private static boolean sourceRegistered;
    private static int counter;
    private UUID installation;
    private UUID repository;
    private UUID changeSet;

    @BeforeEach
    void prepare() {
        int n = ++counter;
        synchronized (ChaosIT.class) {
            if (!sourceRegistered) {
                sources.register(
                        new WatchedSource(
                                "chaos-sdk",
                                SourceKind.MAVEN,
                                "Chaos SDK",
                                "http://upstream.test",
                                null,
                                null,
                                null,
                                null,
                                null,
                                900,
                                true));
                sourceRegistered = true;
            }
        }
        installation = repositories.upsertInstallation(5000L + n, "chaos" + n).id();
        repository =
                repositories
                        .upsertRepository(installation, 6000L + n, "acme/chaos" + n, "main")
                        .id();
        ChangeEvent event =
                ChangeEvent.of(
                        "chaos-sdk",
                        "1." + n,
                        "2." + n,
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod("com.acme.Client", "charge", List.of("long")),
                        Map.of(Attr.NAME, "charge", Attr.REPLACEMENT, "createCharge"),
                        "charge renamed");
        changeSet =
                ingestion
                        .ingest(
                                new ChangeSet(
                                        Ids.newId(),
                                        "chaos-sdk",
                                        SourceKind.MAVEN,
                                        "1." + n,
                                        "2." + n,
                                        List.of(event),
                                        List.of(),
                                        Instant.parse("2026-10-01T10:00:00Z")))
                        .changeSetId();
    }

    private HttpResponse<String> call(String method, String path, String key) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void onlyAGlobalAdminKeyMayUseTheEndpoints() throws Exception {
        String writer =
                keys.issue(installation, "writer", EnumSet.of(Scope.READ, Scope.WRITE)).key();
        String scopedAdmin =
                keys.issue(installation, "scoped", EnumSet.of(Scope.READ, Scope.ADMIN)).key();

        assertThat(call("GET", "/api/v1/admin/chaos", null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/admin/chaos", writer).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/v1/admin/chaos/leases/expire", scopedAdmin).statusCode())
                .isEqualTo(403);
        assertThat(call("GET", "/api/v1/admin/chaos", ADMIN).statusCode()).isEqualTo(200);
    }

    @Test
    void statusReportsTheRuntimeAndLeasedJobs() throws Exception {
        UUID job = jobs.create(changeSet, repository, 0, 3).jobId();
        lease(job);

        JsonNode status = Json.tree(call("GET", "/api/v1/admin/chaos", ADMIN).body());

        assertThat(status.get("leasedJobs").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(status.get("workersAlive").asInt()).isZero();
        assertThat(status.get("relayRunning").asBoolean()).isFalse();
    }

    @Test
    void expiringLeasesLetAnotherWorkerTakeTheJobOver() throws Exception {
        UUID job = jobs.create(changeSet, repository, 0, 3).jobId();
        lease(job);
        assertThat(expired(job)).isFalse();

        HttpResponse<String> response = call("POST", "/api/v1/admin/chaos/leases/expire", ADMIN);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.tree(response.body()).get("expired").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(expired(job)).isTrue();
        assertThat(audit.recent(20)).extracting(e -> e.action()).contains("CHAOS_LEASES_EXPIRE");
    }

    @Test
    void crashingWorkersWithoutARuntimeCrashesNone() throws Exception {
        HttpResponse<String> response =
                call("POST", "/api/v1/admin/chaos/workers/crash?count=2", ADMIN);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.tree(response.body()).get("crashed").asInt()).isZero();
        assertThat(call("POST", "/api/v1/admin/chaos/workers/crash?count=0", ADMIN).statusCode())
                .isEqualTo(400);
        assertThat(call("POST", "/api/v1/admin/chaos/workers/crash?count=65", ADMIN).statusCode())
                .isEqualTo(400);
    }

    @Test
    void relayControlsAreSafeWithoutARelay() throws Exception {
        assertThat(call("POST", "/api/v1/admin/chaos/relay/stop", ADMIN).statusCode())
                .isEqualTo(200);
        assertThat(call("POST", "/api/v1/admin/chaos/relay/start", ADMIN).statusCode())
                .isEqualTo(200);
    }

    private void lease(UUID job) {
        jdbc.sql(
                        "update migration_job set lease_owner = 'doomed', lease_expires_at ="
                                + " now() + interval '1 hour' where id = :id")
                .param("id", job)
                .update();
    }

    private boolean expired(UUID job) {
        return jdbc.sql("select lease_expires_at <= now() from migration_job where id = :id")
                .param("id", job)
                .query(Boolean.class)
                .single();
    }
}
