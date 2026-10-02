package dev.rook.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.changes.source.WatchedSource;
import dev.rook.changes.store.ChangeIngestion;
import dev.rook.changes.store.SourceRepository;
import dev.rook.control.audit.AuditLog;
import dev.rook.control.auth.ApiKeys;
import dev.rook.control.auth.Scope;
import dev.rook.control.policy.Decision;
import dev.rook.control.policy.Reason;
import dev.rook.control.store.ApprovalStore;
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
import java.util.HashMap;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

@AutoConfigureMetrics
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "rook.role=api",
            "rook.control.bootstrap-key=bootstrap-key-with-more-than-thirty-two-chars",
            "rook.control.rate-burst=500"
        })
@Testcontainers
class ApiActionsIT {

    private static final String ADMIN = "bootstrap-key-with-more-than-thirty-two-chars";

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired RepositoryStore repositories;
    @Autowired SourceRepository sources;
    @Autowired ChangeIngestion ingestion;
    @Autowired JobRepository jobs;
    @Autowired ApiKeys keys;
    @Autowired ApprovalStore approvals;
    @Autowired AuditLog audit;
    @Autowired JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private static boolean sourceRegistered;
    private UUID installation;
    private UUID otherInstallation;
    private UUID repository;
    private UUID changeSet;
    private String writer;
    private String reader;
    private String stranger;
    private static int counter;

    @BeforeEach
    void prepare() {
        int n = ++counter;
        synchronized (ApiActionsIT.class) {
            if (!sourceRegistered) {
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
                sourceRegistered = true;
            }
        }
        installation = repositories.upsertInstallation(1000L + n, "acme" + n).id();
        otherInstallation = repositories.upsertInstallation(2000L + n, "other" + n).id();
        repository =
                repositories
                        .upsertRepository(installation, 3000L + n, "acme/shop" + n, "main")
                        .id();
        ChangeEvent event =
                ChangeEvent.of(
                        "acme-sdk",
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
                                        "acme-sdk",
                                        SourceKind.MAVEN,
                                        "1." + n,
                                        "2." + n,
                                        List.of(event),
                                        List.of(),
                                        Instant.parse("2026-10-01T10:00:00Z")))
                        .changeSetId();
        writer = keys.issue(installation, "writer" + n, EnumSet.of(Scope.READ, Scope.WRITE)).key();
        reader = keys.issue(installation, "reader" + n, EnumSet.of(Scope.READ)).key();
        stranger =
                keys.issue(otherInstallation, "stranger" + n, EnumSet.of(Scope.READ, Scope.WRITE))
                        .key();
    }

    private HttpResponse<String> call(String method, String path, String key, Object body)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        request.header("Content-Type", "application/json");
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private UUID waitingJob() {
        UUID job = jobs.create(changeSet, repository, 0, 3).jobId();
        jdbc.sql("update migration_job set state = 'AWAITING_APPROVAL' where id = :id")
                .param("id", job)
                .update();
        approvals.request(
                job,
                new Decision(
                        Decision.Outcome.REQUIRE_APPROVAL,
                        List.of(new Reason("LARGE_PATCH_LINES", "big"))));
        return job;
    }

    private String state(UUID job) {
        return jdbc.sql("select state from migration_job where id = :id")
                .param("id", job)
                .query(String.class)
                .single();
    }

    @Test
    void aWriterApprovesAPendingJobExactlyOnce() throws Exception {
        UUID job = waitingJob();
        HttpResponse<String> response =
                call("POST", "/api/v1/jobs/" + job + "/approve", writer, Map.of("note", "ok"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.tree(response.body()).get("result").asString()).isEqualTo("APPLIED");
        assertThat(jobs.find(job).orElseThrow().checkpointText("approval")).isEqualTo("APPROVED");
        assertThat(approvals.find(job).orElseThrow().note()).isEqualTo("ok");
        assertThat(call("POST", "/api/v1/jobs/" + job + "/approve", writer, null).statusCode())
                .isEqualTo(409);
        assertThat(audit.forSubject("job:" + job, 10))
                .extracting(e -> e.action())
                .contains("APPROVED");
    }

    @Test
    void approvalNeedsAWriteKeyForTheRightInstallation() throws Exception {
        UUID job = waitingJob();
        assertThat(call("POST", "/api/v1/jobs/" + job + "/approve", reader, null).statusCode())
                .isEqualTo(403);
        assertThat(call("POST", "/api/v1/jobs/" + job + "/approve", stranger, null).statusCode())
                .isEqualTo(404);
        assertThat(call("POST", "/api/v1/jobs/" + job + "/approve", null, null).statusCode())
                .isEqualTo(401);
        assertThat(state(job)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void aRejectionFinishesTheJob() throws Exception {
        UUID job = waitingJob();
        HttpResponse<String> response =
                call("POST", "/api/v1/jobs/" + job + "/reject", writer, Map.of("note", "no"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(state(job)).isEqualTo("REJECTED");
    }

    @Test
    void jobsCanBeCancelledAndFailedOnesRetried() throws Exception {
        UUID pending = jobs.create(changeSet, repository, 0, 3).jobId();
        assertThat(call("POST", "/api/v1/jobs/" + pending + "/cancel", writer, null).statusCode())
                .isEqualTo(200);
        assertThat(state(pending)).isEqualTo("CANCELLED");
        assertThat(call("POST", "/api/v1/jobs/" + pending + "/cancel", writer, null).statusCode())
                .isEqualTo(409);

        UUID failed = jobs.create(changeSet, repository, 0, 3).jobId();
        assertThat(call("POST", "/api/v1/jobs/" + failed + "/retry", writer, null).statusCode())
                .isEqualTo(409);
        jdbc.sql("update migration_job set state = 'FAILED', finished_at = now() where id = :id")
                .param("id", failed)
                .update();
        assertThat(call("POST", "/api/v1/jobs/" + failed + "/retry", writer, null).statusCode())
                .isEqualTo(200);
        assertThat(state(failed)).isEqualTo("PENDING");
    }

    @Test
    void replayingAChangeSetIsAnAdminAction() throws Exception {
        assertThat(
                        call("POST", "/api/v1/change-sets/" + changeSet + "/replay", writer, null)
                                .statusCode())
                .isEqualTo(403);
        int before = jdbc.sql("select count(*) from outbox").query(Integer.class).single();
        HttpResponse<String> response =
                call("POST", "/api/v1/change-sets/" + changeSet + "/replay", ADMIN, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from outbox").query(Integer.class).single())
                .isGreaterThan(before);
        assertThat(
                        call(
                                        "POST",
                                        "/api/v1/change-sets/" + UUID.randomUUID() + "/replay",
                                        ADMIN,
                                        null)
                                .statusCode())
                .isEqualTo(404);
    }

    @Test
    void policiesAreVersionedAndValidated() throws Exception {
        Map<String, Object> policy =
                Map.ofEntries(
                        Map.entry("maxFilesChanged", 3),
                        Map.entry("maxChangedLines", 50),
                        Map.entry("allowedPaths", List.of("src/**")),
                        Map.entry("deniedPaths", List.of()),
                        Map.entry("allowBuildFileChanges", false),
                        Map.entry("requireVerified", true),
                        Map.entry("approval", "ALWAYS"),
                        Map.entry("approvalAboveFiles", 1),
                        Map.entry("approvalAboveLines", 10),
                        Map.entry("approvalBelowAgentConfidence", 0.8),
                        Map.entry("approvalForTextualMatches", true),
                        Map.entry("maxJobCostMicros", 1000));
        String path = "/api/v1/policies?installation=" + installation;
        assertThat(call("PUT", path, writer, policy).statusCode()).isEqualTo(403);
        HttpResponse<String> saved = call("PUT", path, ADMIN, policy);
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(Json.tree(saved.body()).get("version").asInt()).isEqualTo(1);
        JsonNode effective =
                Json.tree(call("GET", "/api/v1/policies/effective", writer, null).body());
        assertThat(effective.get("policy").get("approval").asString()).isEqualTo("ALWAYS");
        assertThat(effective.get("policy").get("maxFilesChanged").asInt()).isEqualTo(3);
        Map<String, Object> broken = new HashMap<>(policy);
        broken.put("maxFilesChanged", 0);
        assertThat(call("PUT", path, ADMIN, broken).statusCode()).isEqualTo(400);
        assertThat(
                        call("PUT", path + "&repository=" + UUID.randomUUID(), ADMIN, policy)
                                .statusCode())
                .isEqualTo(404);
        assertThat(audit.forSubject("installation:" + installation, 10))
                .extracting(e -> e.action())
                .contains("POLICY_UPDATED");
    }

    @Test
    void budgetsCanBeSetByAdmins() throws Exception {
        String path = "/api/v1/budgets?installation=" + installation;
        assertThat(
                        call("PUT", path, writer, Map.of("monthlyMicros", 5, "jobMicros", 2))
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        call("PUT", path, ADMIN, Map.of("monthlyMicros", 9_000, "jobMicros", 4_000))
                                .statusCode())
                .isEqualTo(200);
        JsonNode usage = Json.tree(call("GET", "/api/v1/usage", writer, null).body());
        assertThat(usage.get("monthlyCapMicros").asLong()).isEqualTo(9_000);
        assertThat(call("PUT", path, ADMIN, Map.of("monthlyMicros", 9_000)).statusCode())
                .isEqualTo(400);
    }

    @Test
    void keysAreCreatedUsedAndRevokedByAdmins() throws Exception {
        HttpResponse<String> created =
                call(
                        "POST",
                        "/api/v1/api-keys",
                        ADMIN,
                        Map.of(
                                "name",
                                "ci",
                                "scopes",
                                List.of("READ"),
                                "installation",
                                installation));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode key = Json.tree(created.body());
        String secret = key.get("key").asString();
        assertThat(call("GET", "/api/v1/installations", secret, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/api-keys?installation=" + installation, ADMIN, null).body())
                .contains("ci")
                .doesNotContain(secret);
        assertThat(
                        call("DELETE", "/api/v1/api-keys/" + key.get("id").asString(), ADMIN, null)
                                .statusCode())
                .isEqualTo(200);
        assertThat(call("GET", "/api/v1/installations", secret, null).statusCode()).isEqualTo(401);
        assertThat(
                        call("DELETE", "/api/v1/api-keys/" + key.get("id").asString(), ADMIN, null)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        call(
                                        "POST",
                                        "/api/v1/api-keys",
                                        writer,
                                        Map.of("name", "x", "scopes", List.of("READ")))
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    void aScopedAdminCanOnlyManageItsOwnKeysAndNeverMakeAdmins() throws Exception {
        String scopedAdmin =
                keys.issue(installation, "scoped-admin", EnumSet.of(Scope.READ, Scope.ADMIN)).key();
        HttpResponse<String> admin =
                call(
                        "POST",
                        "/api/v1/api-keys",
                        scopedAdmin,
                        Map.of("name", "x", "scopes", List.of("ADMIN")));
        assertThat(admin.statusCode()).isEqualTo(403);
        HttpResponse<String> ok =
                call(
                        "POST",
                        "/api/v1/api-keys",
                        scopedAdmin,
                        Map.of(
                                "name",
                                "x",
                                "scopes",
                                List.of("READ"),
                                "installation",
                                otherInstallation));
        assertThat(ok.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(Json.tree(ok.body()).get("id").asString());
        String foreign = keys.issue(otherInstallation, "foreign", EnumSet.of(Scope.READ)).key();
        UUID foreignId = keys.authenticate(foreign).orElseThrow().keyId();
        assertThat(call("DELETE", "/api/v1/api-keys/" + foreignId, scopedAdmin, null).statusCode())
                .isEqualTo(404);
        assertThat(call("DELETE", "/api/v1/api-keys/" + id, scopedAdmin, null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void rescanningNeedsGithubToBeConfigured() throws Exception {
        assertThat(
                        call("POST", "/api/v1/repositories/" + repository + "/rescan", writer, null)
                                .statusCode())
                .isEqualTo(503);
        assertThat(
                        call(
                                        "POST",
                                        "/api/v1/repositories/" + repository + "/rescan",
                                        stranger,
                                        null)
                                .statusCode())
                .isEqualTo(404);
    }
}
