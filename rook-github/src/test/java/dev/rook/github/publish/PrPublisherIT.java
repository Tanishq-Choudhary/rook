package dev.rook.github.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.error.RookException;
import dev.rook.core.id.Ids;
import dev.rook.github.api.GitHubClient;
import dev.rook.github.auth.AppJwt;
import dev.rook.github.auth.InstallationTokens;
import dev.rook.github.webhook.PullRequests;
import dev.rook.testkit.FakeGitHub;
import dev.rook.testkit.TestClock;
import dev.rook.testkit.TestDatabases;
import dev.rook.workflow.repo.RepositoryStore;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PrPublisherIT {

    private static KeyPair pair;

    @TempDir Path temp;

    private final TestClock clock = TestClock.at("2026-10-01T10:00:00Z");
    private FakeGitHub github;
    private PullRequests pullRequests;
    private PrPublisher publisher;
    private Path remote;
    private GitHubClient.Repo repo;
    private UUID repositoryId;
    private JdbcClient jdbc;

    @BeforeAll
    static void generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() throws Exception {
        remote = temp.resolve("shop.git");
        String url = remote.toUri().toString();
        try (Git bare =
                Git.init()
                        .setBare(true)
                        .setInitialBranch("main")
                        .setDirectory(remote.toFile())
                        .call()) {
            Path seed = temp.resolve("seed");
            try (Git git = Git.init().setInitialBranch("main").setDirectory(seed.toFile()).call()) {
                Files.createDirectories(seed.resolve("src"));
                Files.writeString(seed.resolve("src/A.java"), "class A {}\n");
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
        github = new FakeGitHub(clock);
        github.addRepository(1, 100, "acme/shop", "main", url);
        repo = new GitHubClient.Repo(100, "acme/shop", "main", url);
        DataSource ds = TestDatabases.fresh();
        jdbc = JdbcClient.create(ds);
        RepositoryStore store =
                new RepositoryStore(
                        jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        RepositoryStore.Installation installation = store.upsertInstallation(1, "acme");
        repositoryId = store.upsertRepository(installation.id(), 100, "acme/shop", "main").id();
        jdbc.sql(
                        "insert into watched_source (id, kind, display_name, location) values ('src', 'OPENAPI', 'src', 'http://x')")
                .update();
        pullRequests = new PullRequests(jdbc, clock);
        GitHubClient[] holder = new GitHubClient[1];
        InstallationTokens tokens =
                new InstallationTokens(
                        (jwt, id) -> holder[0].issue(jwt, id),
                        new AppJwt(9, pair.getPrivate(), clock),
                        clock);
        holder[0] =
                new GitHubClient(
                        github.baseUrl(),
                        HttpClient.newHttpClient(),
                        tokens,
                        millis -> {},
                        clock,
                        Duration.ofSeconds(5));
        publisher = new PrPublisher(holder[0], tokens, pullRequests, temp.resolve("work"));
    }

    @AfterEach
    void tearDown() {
        github.close();
    }

    private UUID newJob() {
        UUID changeSet = Ids.newId();
        jdbc.sql(
                        "insert into change_set (id, source_id, change_key, from_version, to_version,"
                                + " highest_severity, requires_migration, detected_at)"
                                + " values (:id, 'src', :key, '1', '2', 'BREAKING', true, now())")
                .param("id", changeSet)
                .param("key", "key-" + changeSet)
                .update();
        UUID job = Ids.newId();
        jdbc.sql(
                        "insert into migration_job (id, change_set_id, repository_id, state)"
                                + " values (:id, :change, :repository, 'PUBLISHING')")
                .param("id", job)
                .param("change", changeSet)
                .param("repository", repositoryId)
                .update();
        return job;
    }

    private PrReport report() {
        return new PrReport(
                "acme/shop",
                "acme-sdk",
                "1.0.0",
                "2.0.0",
                List.of("PayClient.charge is now createCharge"),
                List.of(new PrReport.CallSite("src/A.java", 1, "call")),
                new PrReport.Diff(1, 1, 1),
                new PrReport.Verification(5, 0, 5, 0, List.of(), List.of("build", "tests")),
                List.of(),
                "codemod-only",
                null);
    }

    private PrPublisher.Request request(UUID job, Map<String, String> files) {
        return new PrPublisher.Request(
                1, repositoryId, repo, job, "acme-sdk 2.0.0", files, List.of(), report());
    }

    private String remoteBranch(String branch) throws Exception {
        try (Git git = Git.open(remote.toFile())) {
            ObjectId id = git.getRepository().resolve("refs/heads/" + branch);
            return id == null ? null : id.name();
        }
    }

    private RevCommit commit(String sha) throws Exception {
        try (Git git = Git.open(remote.toFile());
                RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(ObjectId.fromString(sha));
        }
    }

    @Test
    void thePatchBecomesABranchACommitByTheBotAndAPullRequest() throws Exception {
        UUID job = newJob();
        PrPublisher.Published published =
                publisher.publish(request(job, Map.of("src/A.java", "class A { int v; }\n")));
        assertThat(published.created()).isTrue();
        assertThat(published.number()).isEqualTo(1);
        assertThat(published.branch()).isEqualTo(PrPublisher.branchName(job, "acme-sdk 2.0.0"));
        assertThat(published.url()).isEqualTo("https://github.test/acme/shop/pull/1");
        String sha = remoteBranch(published.branch());
        assertThat(sha).isNotNull();
        assertThat(commit(sha).getAuthorIdent().getName()).isEqualTo("rook[bot]");
        assertThat(github.pulls()).hasSize(1);
        FakeGitHub.Pull pull = github.pulls().get(0);
        assertThat(pull.head()).isEqualTo(published.branch());
        assertThat(pull.base()).isEqualTo("main");
        assertThat(pull.title()).isEqualTo("Update for acme-sdk 1.0.0 to 2.0.0");
        assertThat(pull.body()).contains("## Verification").contains("5 tests ran, 0 failed");
        PullRequests.Record stored = pullRequests.findByJob(job).orElseThrow();
        assertThat(stored.number()).isEqualTo(1);
        assertThat(stored.state()).isEqualTo(PullRequests.State.OPEN);
        assertThat(github.checkRuns())
                .singleElement()
                .satisfies(
                        run -> {
                            assertThat(run.conclusion()).isEqualTo("success");
                            assertThat(run.name()).isEqualTo("rook");
                        });
        assertThat(temp.resolve("work/publish-" + job)).doesNotExist();
    }

    @Test
    void publishingTheSameJobAgainReturnsTheExistingPullRequest() throws Exception {
        UUID job = newJob();
        PrPublisher.Request request = request(job, Map.of("src/A.java", "class A { int v; }\n"));
        PrPublisher.Published first = publisher.publish(request);
        String sha = remoteBranch(first.branch());
        PrPublisher.Published second = publisher.publish(request);
        assertThat(second.created()).isFalse();
        assertThat(second.number()).isEqualTo(first.number());
        assertThat(remoteBranch(first.branch())).isEqualTo(sha);
        assertThat(github.pulls()).hasSize(1);
        assertThat(jdbc.sql("select count(*) from pull_request").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void aFailureAfterThePushIsRepairedByTheNextAttemptWithoutANewCommit() throws Exception {
        UUID job = newJob();
        PrPublisher.Request request = request(job, Map.of("src/A.java", "class A { int v; }\n"));
        for (int i = 0; i < 4; i++) {
            github.failNext("POST", "/pulls", 500, Map.of());
        }
        assertThatThrownBy(() -> publisher.publish(request)).isInstanceOf(RuntimeException.class);
        String branch = PrPublisher.branchName(job, "acme-sdk 2.0.0");
        String pushed = remoteBranch(branch);
        assertThat(pushed).isNotNull();
        assertThat(github.pulls()).isEmpty();
        PrPublisher.Published retried = publisher.publish(request);
        assertThat(retried.created()).isTrue();
        assertThat(remoteBranch(branch)).isEqualTo(pushed);
        assertThat(github.pulls()).hasSize(1);
    }

    @Test
    void aPullRequestOpenedMeanwhileIsAdoptedInsteadOfDuplicated() throws Exception {
        UUID job = newJob();
        PrPublisher.Request request = request(job, Map.of("src/A.java", "class A { int v; }\n"));
        publisher.publish(request);
        github.failNext("GET", "/pulls", 200, Map.of());
        PrPublisher.Published again = publisher.publish(request);
        assertThat(again.created()).isFalse();
        assertThat(github.pulls()).hasSize(1);
    }

    @Test
    void aPatchThatChangesNothingIsRefusedAndLeavesNoBranch() throws Exception {
        UUID job = newJob();
        assertThatThrownBy(
                        () -> publisher.publish(request(job, Map.of("src/A.java", "class A {}\n"))))
                .isInstanceOf(RookException.class)
                .hasMessageContaining("does not change");
        assertThat(remoteBranch(PrPublisher.branchName(job, "acme-sdk 2.0.0"))).isNull();
        assertThat(github.pulls()).isEmpty();
    }

    @Test
    void aFailingCheckRunDoesNotFailThePublication() {
        github.failNext("/check-runs", 403, Map.of());
        PrPublisher.Published published =
                publisher.publish(request(newJob(), Map.of("src/A.java", "class A { int v; }\n")));
        assertThat(published.created()).isTrue();
        assertThat(github.checkRuns()).isEmpty();
    }

    @Test
    void branchNamesAreStableShortAndSafe() {
        UUID job = UUID.fromString("0192a3b4-0000-7000-8000-000000000000");
        assertThat(PrPublisher.branchName(job, "Acme SDK 2.0.0"))
                .isEqualTo("rook/0192a3b4-acme-sdk-2-0-0");
        assertThat(PrPublisher.branchName(job, "!!!")).isEqualTo("rook/0192a3b4");
        assertThat(PrPublisher.branchName(job, null)).isEqualTo("rook/0192a3b4");
        assertThat(PrPublisher.branchName(job, "x".repeat(100)))
                .hasSize("rook/0192a3b4-".length() + 40);
        assertThat(PrPublisher.branchName(job, "a b"))
                .isEqualTo(PrPublisher.branchName(job, "a b"));
    }
}
