package dev.rook.server.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rook.changes.store.ChangeIngestion;
import dev.rook.changes.store.SourceRepository;
import dev.rook.control.audit.AuditLog;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import dev.rook.pipeline.verify.PatchVerifier;
import dev.rook.testkit.FakeGitHub;
import dev.rook.testkit.TestClock;
import dev.rook.testkit.TestKafka;
import dev.rook.verify.build.RunSummary;
import dev.rook.verify.build.TestCaseResult;
import dev.rook.verify.build.TestReport;
import dev.rook.verify.compare.Comparison;
import dev.rook.verify.gate.GateReport;
import dev.rook.verify.gate.GateResult;
import dev.rook.verify.run.VerificationOutcome;
import dev.rook.workflow.repo.RepositoryStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@AutoConfigureMetrics
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "rook.changes.scheduler-enabled=false",
            "rook.workflow.fanout-group=full-flow-it",
            "rook.workflow.relay-poll-interval=PT0.05S",
            "rook.workflow.idle-sleep=PT0.05S",
            "rook.workflow.workers=2"
        })
@Testcontainers
class FullFlowIT {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final FakeGitHub GITHUB = new FakeGitHub(TestClock.at("2026-10-01T10:00:00Z"));
    static final Path WORK = tempDirectory();

    private static Path tempDirectory() {
        try {
            return Files.createTempDirectory("rook-full-flow");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String key =
                Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        registry.add("rook.workflow.bootstrap-servers", TestKafka::bootstrapServers);
        registry.add("rook.github.app-id", () -> "9");
        registry.add(
                "rook.github.private-key",
                () -> "-----BEGIN PRIVATE KEY-----\n" + key + "\n-----END PRIVATE KEY-----\n");
        registry.add("rook.github.webhook-secret", () -> "a-webhook-secret-with-enough-length");
        registry.add("rook.github.api-url", GITHUB::baseUrl);
        registry.add("rook.pipeline.work-dir", () -> WORK.resolve("work").toString());
    }

    @AfterAll
    static void stop() {
        GITHUB.close();
    }

    @TestConfiguration
    static class PassingVerifier {

        @Bean
        @Primary
        PatchVerifier verifier() {
            return input -> {
                RunSummary run =
                        new RunSummary(
                                RunSummary.Status.TESTS_RAN,
                                TestReport.of(
                                        List.of(
                                                new TestCaseResult(
                                                        "com.shop.ServiceTest#go",
                                                        TestCaseResult.Outcome.PASSED,
                                                        "",
                                                        0.1))),
                                "");
                return new VerificationOutcome(
                        true,
                        null,
                        new GateReport(List.of(GateResult.pass("compiles"))),
                        run,
                        run,
                        Comparison.compare(run.report(), run.report()),
                        null,
                        true,
                        Duration.ofSeconds(1));
            };
        }
    }

    @Autowired RepositoryStore repositories;
    @Autowired SourceRepository sources;
    @Autowired ChangeIngestion ingestion;
    @Autowired AuditLog audit;
    @Autowired JdbcClient jdbc;

    private String seedRemote() throws Exception {
        Path remote = WORK.resolve("shop.git");
        String url = remote.toUri().toString();
        try (Git bare =
                Git.init()
                        .setBare(true)
                        .setInitialBranch("main")
                        .setDirectory(remote.toFile())
                        .call()) {
            Path seed = WORK.resolve("seed");
            try (Git git = Git.init().setInitialBranch("main").setDirectory(seed.toFile()).call()) {
                Path service = seed.resolve("src/main/java/com/shop/Service.java");
                Files.createDirectories(service.getParent());
                Files.writeString(
                        service,
                        "package com.shop;\n\nimport com.acme.pay.PaymentClient;\n\npublic class Service {\n"
                                + "    private final PaymentClient client = new PaymentClient();\n\n"
                                + "    public String go() {\n        return client.charge(5);\n    }\n}\n");
                Files.writeString(
                        seed.resolve("pom.xml"),
                        "<project>\n  <modelVersion>4.0.0</modelVersion>\n  <groupId>com.shop</groupId>\n"
                                + "  <artifactId>shop</artifactId>\n  <version>1</version>\n</project>\n");
                git.add().addFilepattern(".").call();
                PersonIdent owner = new PersonIdent("Owner", "owner@example.test");
                git.commit()
                        .setMessage("initial")
                        .setAuthor(owner)
                        .setCommitter(owner)
                        .setSign(false)
                        .call();
                git.push().setRemote(url).add("refs/heads/main:refs/heads/main").call();
            }
        }
        return url;
    }

    @Test
    void aDetectedChangeTravelsFromIngestionToAPullRequestOnItsOwn() throws Exception {
        String remoteUrl = seedRemote();
        GITHUB.addRepository(1, 100, "acme/shop", "main", remoteUrl);
        jdbc.sql(
                        "insert into watched_source (id, kind, display_name, location, config)"
                                + " values ('acme-sdk', 'MAVEN', 'acme-sdk', 'http://x', '{}'::jsonb)")
                .update();
        RepositoryStore.Installation installation = repositories.upsertInstallation(1, "acme");
        UUID repository =
                repositories.upsertRepository(installation.id(), 100, "acme/shop", "main").id();
        repositories.watch(repository, "acme-sdk");

        ChangeEvent rename =
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod(
                                "com.acme.pay.PaymentClient", "charge", List.of("long")),
                        Map.of(Attr.NAME, "charge", Attr.REPLACEMENT, "createCharge"),
                        "charge renamed to createCharge");
        ingestion.ingest(
                new ChangeSet(
                        Ids.newId(),
                        "acme-sdk",
                        SourceKind.MAVEN,
                        "1.0.0",
                        "2.0.0",
                        List.of(rename),
                        List.of(),
                        Instant.parse("2026-10-01T10:00:00Z")));

        await().atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.sql(
                                                                "select state from migration_job where repository_id = :r")
                                                        .param("r", repository)
                                                        .query(String.class)
                                                        .optional())
                                        .contains("COMPLETED"));

        assertThat(GITHUB.pulls()).hasSize(1);
        FakeGitHub.Pull pull = GITHUB.pulls().get(0);
        assertThat(pull.repository()).isEqualTo("acme/shop");
        assertThat(pull.body()).contains("charge renamed to createCharge").contains("codemod-only");
        assertThat(
                        Json.write(
                                audit.forSubject("job:" + jobId(repository), 20).stream()
                                        .map(e -> e.action())
                                        .toList()))
                .contains("IMPACT_ANALYZED", "PATCH_PROPOSED", "POLICY_DECIDED", "PR_OPENED");
        assertThat(audit.verify().intact()).isTrue();
        assertThat(
                        jdbc.sql("select count(*) from outbox where published_at is null")
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    private UUID jobId(UUID repository) {
        return jdbc.sql("select id from migration_job where repository_id = :r")
                .param("r", repository)
                .query(UUID.class)
                .single();
    }
}
