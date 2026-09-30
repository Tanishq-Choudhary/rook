package dev.rook.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.changes.source.WatchedSource;
import dev.rook.changes.store.ChangeIngestion;
import dev.rook.changes.store.SourceRepository;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.control.audit.AuditLog;
import dev.rook.control.auth.ApiKeys;
import dev.rook.control.auth.Scope;
import dev.rook.control.budget.BudgetService;
import dev.rook.control.budget.SpendKind;
import dev.rook.control.policy.Decision;
import dev.rook.control.policy.Reason;
import dev.rook.control.store.ApprovalStore;
import dev.rook.control.store.PolicyStore;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import dev.rook.pipeline.store.ArtifactStore;
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
class ApiReadIT {

    private static final String ADMIN = "bootstrap-key-with-more-than-thirty-two-chars";

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired RepositoryStore repositories;
    @Autowired SourceRepository sources;
    @Autowired ChangeIngestion ingestion;
    @Autowired JobRepository jobs;
    @Autowired ApiKeys keys;
    @Autowired ArtifactStore artifacts;
    @Autowired PolicyStore policies;
    @Autowired ApprovalStore approvals;
    @Autowired BudgetService budgets;
    @Autowired AuditLog audit;

    private final HttpClient http = HttpClient.newHttpClient();

    private static UUID acme;
    private static UUID other;
    private static UUID shop;
    private static UUID blog;
    private static UUID job;
    private static UUID changeSet;
    private static String acmeKey;
    private static String otherKey;
    private static boolean seeded;

    private synchronized void seed() {
        if (seeded) {
            return;
        }
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
        acme = repositories.upsertInstallation(11, "acme").id();
        other = repositories.upsertInstallation(12, "other").id();
        shop = repositories.upsertRepository(acme, 110, "acme/shop", "main").id();
        blog = repositories.upsertRepository(acme, 111, "acme/blog", "main").id();
        UUID stranger = repositories.upsertRepository(other, 120, "other/app", "main").id();
        repositories.watch(shop, "acme-sdk");
        ChangeEvent rename =
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod("com.acme.Client", "charge", List.of("long")),
                        Map.of(Attr.NAME, "charge", Attr.REPLACEMENT, "createCharge"),
                        "charge renamed to createCharge");
        changeSet =
                ingestion
                        .ingest(
                                new ChangeSet(
                                        Ids.newId(),
                                        "acme-sdk",
                                        SourceKind.MAVEN,
                                        "1.0.0",
                                        "2.0.0",
                                        List.of(rename),
                                        List.of(),
                                        Instant.parse("2026-10-01T10:00:00Z")))
                        .changeSetId();
        job = jobs.create(changeSet, shop, 0, 3).jobId();
        jobs.create(changeSet, stranger, 0, 3);
        artifacts.saveImpact(
                job,
                "NEEDS_MIGRATION",
                "1 of 1 changes touch the repository",
                "a".repeat(40),
                ImpactReport.of(List.of()));
        artifacts.saveProposal(
                new ArtifactStore.NewProposal(
                        job,
                        0,
                        "codemod-only",
                        "COMPLETE",
                        null,
                        null,
                        "diff --git a/A b/A",
                        Map.of("src/A.java", "class A {}"),
                        List.of(),
                        List.of("src/B.java:9 METHOD_CALL"),
                        Map.of(),
                        120,
                        List.of()));
        artifacts.saveVerification(
                new ArtifactStore.NewVerification(
                        job,
                        0,
                        true,
                        true,
                        "all gates passed",
                        10,
                        0,
                        10,
                        0,
                        Map.of("gates", List.of()),
                        900));
        policies.recordDecision(
                job,
                new Decision(
                        Decision.Outcome.REQUIRE_APPROVAL,
                        List.of(new Reason("LARGE_PATCH_LINES", "big"))),
                1);
        approvals.request(
                job,
                new Decision(
                        Decision.Outcome.REQUIRE_APPROVAL,
                        List.of(new Reason("LARGE_PATCH_LINES", "big"))));
        budgets.record(acme, job, SpendKind.MODEL, 1000, 120);
        audit.append(
                "rook", "IMPACT_ANALYZED", "job:" + job, Map.of("decision", "NEEDS_MIGRATION"));
        acmeKey = keys.issue(acme, "acme-reader", EnumSet.of(Scope.READ)).key();
        otherKey = keys.issue(other, "other-reader", EnumSet.of(Scope.READ)).key();
        seeded = true;
    }

    @BeforeEach
    void prepare() {
        seed();
    }

    private HttpResponse<String> get(String path, String key) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String path, String key) throws Exception {
        HttpResponse<String> response = get(path, key);
        assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
        return Json.tree(response.body());
    }

    @Test
    void everyEndpointNeedsAKey() throws Exception {
        for (String path :
                List.of(
                        "/api/v1/installations",
                        "/api/v1/jobs",
                        "/api/v1/sources",
                        "/api/v1/usage")) {
            assertThat(get(path, null).statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void aScopedKeyOnlySeesItsOwnInstallation() throws Exception {
        JsonNode installations = json("/api/v1/installations", acmeKey);
        assertThat(installations.size()).isEqualTo(1);
        assertThat(installations.get(0).get("accountLogin").asString()).isEqualTo("acme");
        assertThat(get("/api/v1/installations/" + other, acmeKey).statusCode()).isEqualTo(404);
        assertThat(json("/api/v1/installations/" + acme, acmeKey).get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(json("/api/v1/installations", ADMIN).size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void repositoriesArePagedAndCarryTheirWatches() throws Exception {
        JsonNode page = json("/api/v1/repositories?size=1", acmeKey);
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("fullName").asString()).isEqualTo("acme/blog");
        JsonNode second = json("/api/v1/repositories?size=1&page=1", acmeKey);
        assertThat(second.get("items").get(0).get("watches").get(0).asString())
                .isEqualTo("acme-sdk");
        assertThat(get("/api/v1/repositories?installation=" + other, acmeKey).statusCode())
                .isEqualTo(404);
        assertThat(json("/api/v1/repositories/" + shop, acmeKey).get("defaultBranch").asString())
                .isEqualTo("main");
        assertThat(get("/api/v1/repositories/" + UUID.randomUUID(), acmeKey).statusCode())
                .isEqualTo(404);
        assertThat(json("/api/v1/repositories?size=5000", ADMIN).get("size").asInt())
                .isEqualTo(100);
        assertThat(blog).isNotNull();
    }

    @Test
    void changeSetsListWithTheirEvents() throws Exception {
        JsonNode page = json("/api/v1/change-sets?source=acme-sdk", acmeKey);
        assertThat(page.get("items").get(0).get("events").asInt()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("highestSeverity").asString())
                .isEqualTo("BREAKING");
        JsonNode detail = json("/api/v1/change-sets/" + changeSet, otherKey);
        assertThat(detail.get("events").get(0).get("summary").asString())
                .isEqualTo("charge renamed to createCharge");
        assertThat(detail.get("events").get(0).get("attributes").get("replacement").asString())
                .isEqualTo("createCharge");
        assertThat(json("/api/v1/sources", acmeKey).get(0).get("id").asString())
                .isEqualTo("acme-sdk");
    }

    @Test
    void jobsAreScopedFilteredAndDescribedInFull() throws Exception {
        JsonNode list = json("/api/v1/jobs", acmeKey);
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("repository").asString()).isEqualTo("acme/shop");
        assertThat(json("/api/v1/jobs?state=PENDING", ADMIN).get("total").asLong())
                .isGreaterThanOrEqualTo(2);
        assertThat(json("/api/v1/jobs?state=COMPLETED", acmeKey).get("total").asLong()).isZero();
        assertThat(get("/api/v1/jobs?state=drop%20table", acmeKey).statusCode()).isEqualTo(400);

        JsonNode detail = json("/api/v1/jobs/" + job, acmeKey);
        assertThat(detail.get("job").get("state").asString()).isEqualTo("PENDING");
        assertThat(detail.get("timeline").get(0).get("kind").asString()).isEqualTo("JOB_CREATED");
        assertThat(detail.get("impact").get("decision").asString()).isEqualTo("NEEDS_MIGRATION");
        assertThat(detail.get("proposal").get("strategy").asString()).isEqualTo("codemod-only");
        assertThat(detail.get("proposal").get("changedFiles").get(0).asString())
                .isEqualTo("src/A.java");
        assertThat(detail.get("proposal").toString()).doesNotContain("class A {}");
        assertThat(detail.get("verification").get("passed").asBoolean()).isTrue();
        assertThat(detail.get("policy").get("outcome").asString()).isEqualTo("REQUIRE_APPROVAL");
        assertThat(detail.get("approval").get("status").asString()).isEqualTo("PENDING");
        assertThat(detail.get("spendMicros").asLong()).isEqualTo(120);
        assertThat(get("/api/v1/jobs/" + job, otherKey).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/jobs/" + UUID.randomUUID(), ADMIN).statusCode()).isEqualTo(404);
    }

    @Test
    void usageAndPoliciesAreReadablePerInstallation() throws Exception {
        JsonNode usage = json("/api/v1/usage", acmeKey);
        assertThat(usage.get("spentMicros").asLong()).isEqualTo(120);
        assertThat(usage.get("modelMicros").asLong()).isEqualTo(120);
        assertThat(usage.get("sandboxMicros").asLong()).isZero();
        assertThat(get("/api/v1/usage", ADMIN).statusCode()).isEqualTo(400);
        assertThat(json("/api/v1/usage?installation=" + acme, ADMIN).get("spentMicros").asLong())
                .isEqualTo(120);
        JsonNode policy = json("/api/v1/policies/effective", acmeKey);
        assertThat(policy.get("version").asInt()).isZero();
        assertThat(policy.get("policy").get("maxFilesChanged").asInt()).isEqualTo(10);
    }

    @Test
    void theAuditTrailIsForAdminsOnlyAndVerifiable() throws Exception {
        assertThat(get("/api/v1/audit", acmeKey).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/audit/verify", acmeKey).statusCode()).isEqualTo(403);
        JsonNode entries = json("/api/v1/audit?subject=job:" + job, ADMIN);
        assertThat(entries.get(0).get("action").asString()).isEqualTo("IMPACT_ANALYZED");
        assertThat(json("/api/v1/audit", ADMIN).size()).isGreaterThanOrEqualTo(1);
        JsonNode verification = json("/api/v1/audit/verify", ADMIN);
        assertThat(verification.get("intact").asBoolean()).isTrue();
        assertThat(verification.get("head").asString()).hasSize(64);
    }
}
