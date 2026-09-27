package dev.rook.pipeline;

import dev.rook.changes.store.ChangeIngestion;
import dev.rook.control.audit.AuditLog;
import dev.rook.control.budget.BudgetService;
import dev.rook.control.store.PolicyStore;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.MigrationNote;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.id.Ids;
import dev.rook.github.api.GitHubClient;
import dev.rook.github.auth.AppJwt;
import dev.rook.github.auth.InstallationTokens;
import dev.rook.github.webhook.PullRequests;
import dev.rook.pipeline.access.GitHubRepositoryAccess;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.testkit.FakeGitHub;
import dev.rook.testkit.TestClock;
import dev.rook.testkit.TestDatabases;
import dev.rook.workflow.JobRepository;
import dev.rook.workflow.JobRunner;
import dev.rook.workflow.RunnerConfig;
import dev.rook.workflow.StepHandler;
import dev.rook.workflow.WorkflowMetrics;
import dev.rook.workflow.repo.RepositoryStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import javax.sql.DataSource;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PipelineHarness implements AutoCloseable {

    public static final String SERVICE = "src/main/java/com/shop/Service.java";

    public static final String SERVICE_SOURCE =
            "package com.shop;\n\nimport com.acme.pay.PaymentClient;\n\npublic class Service {\n"
                    + "    private final PaymentClient client = new PaymentClient();\n\n"
                    + "    public String go() {\n        return client.charge(5);\n    }\n}\n";

    public static final String POM =
            "<project>\n  <modelVersion>4.0.0</modelVersion>\n  <groupId>com.shop</groupId>\n"
                    + "  <artifactId>shop</artifactId>\n  <version>1</version>\n</project>\n";

    private static KeyPair pair;

    public final Path root;
    public final TestClock clock = TestClock.at("2026-10-01T10:00:00Z");
    public final DataSource dataSource;
    public final JdbcClient jdbc;
    public final TransactionTemplate tx;
    public final FakeGitHub github;
    public final GitHubClient client;
    public final InstallationTokens tokens;
    public final RepositoryStore repositories;
    public final JobRepository jobs;
    public final ChangeIngestion ingestion;
    public final ArtifactStore artifacts;
    public final AuditLog audit;
    public final PullRequests pullRequests;
    public final PolicyStore policies;
    public final BudgetService budgets;
    public final GitHubRepositoryAccess access;
    public final RepositoryStore.Installation installation;
    public final Path workRoot;
    public UUID repositoryId;
    public Path remote;

    public PipelineHarness(Path root) {
        this.root = root;
        this.workRoot = root.resolve("work");
        dataSource = TestDatabases.fresh();
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql(
                        "insert into watched_source (id, kind, display_name, location)"
                                + " values ('acme-sdk', 'MAVEN', 'acme-sdk', 'http://x')")
                .update();
        github = new FakeGitHub(clock);
        repositories = new RepositoryStore(jdbc, tx);
        jobs = new JobRepository(jdbc, tx, clock);
        ingestion = new ChangeIngestion(jdbc, tx, clock);
        artifacts = new ArtifactStore(jdbc);
        audit = new AuditLog(jdbc, tx, clock);
        pullRequests = new PullRequests(jdbc, clock);
        policies = new PolicyStore(jdbc, tx);
        budgets = new BudgetService(jdbc, tx, clock, new BudgetService.Caps(10_000_000, 5_000_000));
        installation = repositories.upsertInstallation(1, "acme");
        GitHubClient[] holder = new GitHubClient[1];
        tokens =
                new InstallationTokens(
                        (jwt, id) -> holder[0].issue(jwt, id),
                        new AppJwt(9, key().getPrivate(), clock),
                        clock);
        holder[0] =
                new GitHubClient(
                        github.baseUrl(),
                        HttpClient.newHttpClient(),
                        tokens,
                        millis -> {},
                        clock,
                        Duration.ofSeconds(5));
        client = holder[0];
        access = new GitHubRepositoryAccess(repositories, client, tokens);
    }

    private static synchronized KeyPair key() {
        if (pair == null) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                pair = generator.generateKeyPair();
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        return pair;
    }

    public void seedRepository(String fullName, Map<String, String> files) {
        try {
            remote = root.resolve(fullName.replace('/', '-') + ".git");
            String url = remote.toUri().toString();
            try (Git bare =
                    Git.init()
                            .setBare(true)
                            .setInitialBranch("main")
                            .setDirectory(remote.toFile())
                            .call()) {
                Path seed = root.resolve("seed-" + fullName.replace('/', '-'));
                try (Git git =
                        Git.init().setInitialBranch("main").setDirectory(seed.toFile()).call()) {
                    for (Map.Entry<String, String> file : files.entrySet()) {
                        Path target = seed.resolve(file.getKey());
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, file.getValue());
                    }
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
            github.addRepository(1, 100, fullName, "main", url);
            repositoryId =
                    repositories.upsertRepository(installation.id(), 100, fullName, "main").id();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void seedShop() {
        seedRepository("acme/shop", Map.of(SERVICE, SERVICE_SOURCE, "pom.xml", POM));
    }

    public static ChangeEvent renameEvent() {
        return ChangeEvent.of(
                "acme-sdk",
                "1.0.0",
                "2.0.0",
                ChangeKind.METHOD_RENAMED,
                SymbolRef.javaMethod("com.acme.pay.PaymentClient", "charge", List.of("long")),
                Map.of(Attr.NAME, "charge", Attr.REPLACEMENT, "createCharge"),
                "charge renamed to createCharge");
    }

    public UUID changeSet(List<ChangeEvent> events) {
        ChangeSet set =
                new ChangeSet(
                        Ids.newId(),
                        "acme-sdk",
                        SourceKind.MAVEN,
                        "1.0.0",
                        "2.0.0",
                        events,
                        List.of(
                                new MigrationNote(
                                        "Guide", "Use createCharge.", "https://example.test")),
                        clock.instant());
        return ingestion.ingest(set).changeSetId();
    }

    public UUID job(List<ChangeEvent> events) {
        return jobs.create(changeSet(events), repositoryId, 0, 3).jobId();
    }

    public JobRunner runner(List<StepHandler> handlers) {
        return new JobRunner(
                jobs,
                handlers,
                RunnerConfig.standard().withStepTimeout(Duration.ofMinutes(3)),
                WorkflowMetrics.NOOP,
                RandomGenerator.of("L64X128MixRandom"));
    }

    public String state(UUID job) {
        return jdbc.sql("select state from migration_job where id = :id")
                .param("id", job)
                .query(String.class)
                .single();
    }

    @Override
    public void close() {
        github.close();
    }
}
