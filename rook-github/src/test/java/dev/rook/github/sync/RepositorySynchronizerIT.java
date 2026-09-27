package dev.rook.github.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.rook.changes.source.WatchedSource;
import dev.rook.changes.store.SourceRepository;
import dev.rook.core.change.SourceKind;
import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import dev.rook.github.api.GitHubClient;
import dev.rook.github.auth.AppJwt;
import dev.rook.github.auth.InstallationTokens;
import dev.rook.github.webhook.DeliveryLog;
import dev.rook.github.webhook.PullRequests;
import dev.rook.github.webhook.WebhookProcessor;
import dev.rook.testkit.FakeGitHub;
import dev.rook.testkit.TestClock;
import dev.rook.testkit.TestDatabases;
import dev.rook.workflow.repo.RepositoryStore;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RepositorySynchronizerIT {

    private static final String POM =
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>acme</groupId>
              <artifactId>shop</artifactId>
              <version>1</version>
              <dependencies>
                <dependency>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-sdk</artifactId>
                  <version>1.4.0</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private static KeyPair pair;

    @TempDir Path temp;

    private final TestClock clock = TestClock.at("2026-10-01T10:00:00Z");
    private FakeGitHub github;
    private RepositoryStore repositories;
    private SourceRepository sources;
    private RepositorySynchronizer synchronizer;
    private Path remote;
    private String remoteUrl;
    private RepositoryStore.Installation installation;
    private JdbcClient jdbc;

    @BeforeAll
    static void generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
    }

    private void seed(String rookYml) throws Exception {
        remote = temp.resolve("shop.git");
        remoteUrl = remote.toUri().toString();
        try (Git bare =
                Git.init()
                        .setBare(true)
                        .setInitialBranch("main")
                        .setDirectory(remote.toFile())
                        .call()) {
            Path seed = temp.resolve("seed-" + System.nanoTime());
            try (Git git = Git.init().setInitialBranch("main").setDirectory(seed.toFile()).call()) {
                Files.writeString(seed.resolve("pom.xml"), POM);
                if (rookYml != null) {
                    Files.writeString(seed.resolve(".rook.yml"), rookYml);
                }
                git.add().addFilepattern(".").call();
                PersonIdent owner = new PersonIdent("Owner", "owner@example.test");
                git.commit()
                        .setMessage("initial")
                        .setAuthor(owner)
                        .setCommitter(owner)
                        .setSign(false)
                        .call();
                git.push().setRemote(remoteUrl).add("refs/heads/main:refs/heads/main").call();
            }
        }
    }

    @BeforeEach
    void setUp() {
        github = new FakeGitHub(clock);
        DataSource ds = TestDatabases.fresh();
        jdbc = JdbcClient.create(ds);
        repositories =
                new RepositoryStore(
                        jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        sources = new SourceRepository(jdbc);
        installation = repositories.upsertInstallation(1, "acme");
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
        synchronizer =
                new RepositorySynchronizer(
                        holder[0], tokens, repositories, sources, temp.resolve("work"));
        sources.register(
                source(
                        "acme-sdk",
                        SourceKind.MAVEN,
                        Map.of("groupId", "com.acme", "artifactId", "acme-sdk")));
        sources.register(source("acme-api", SourceKind.OPENAPI, Map.of()));
        sources.register(
                source(
                        "other-lib",
                        SourceKind.MAVEN,
                        Map.of("groupId", "org.other", "artifactId", "lib")));
    }

    @AfterEach
    void tearDown() {
        github.close();
    }

    private static WatchedSource source(String id, SourceKind kind, Map<String, String> config) {
        return new WatchedSource(
                id,
                kind,
                id,
                "http://upstream.test/" + id,
                Json.tree(config),
                null,
                null,
                null,
                null,
                900,
                true);
    }

    @Test
    void anInstallationSyncAddsNewRepositoriesAndDisablesTheOnesThatDisappeared() {
        repositories.upsertRepository(installation.id(), 100, "acme/old-name", "master");
        repositories.upsertRepository(installation.id(), 300, "acme/gone", "main");
        github.addRepository(1, 100, "acme/shop", "main", "file:///x");
        github.addRepository(1, 200, "acme/blog", "trunk", "file:///y");
        RepositorySynchronizer.SyncResult result = synchronizer.syncInstallation(1);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.disabled()).isEqualTo(1);
        assertThat(repositories.list(installation.id()))
                .filteredOn(RepositoryStore.Repository::enabled)
                .extracting(
                        RepositoryStore.Repository::fullName,
                        RepositoryStore.Repository::defaultBranch)
                .containsExactlyInAnyOrder(tuple("acme/shop", "main"), tuple("acme/blog", "trunk"));
        assertThat(repositories.list(installation.id()))
                .filteredOn(r -> r.fullName().equals("acme/gone"))
                .singleElement()
                .satisfies(r -> assertThat(r.enabled()).isFalse());
    }

    @Test
    void syncingAnUnknownInstallationFails() {
        assertThatThrownBy(() -> synchronizer.syncInstallation(99))
                .isInstanceOf(RookException.class);
    }

    @Test
    void aPushRefreshesWatchesFromTheConfigurationAndTheDependencies() throws Exception {
        seed("version: 1\nwatch:\n  - source: acme-api\n  - source: not-registered\n");
        github.addRepository(1, 100, "acme/shop", "main", remoteUrl);
        UUID shop = repositories.upsertRepository(installation.id(), 100, "acme/shop", "main").id();
        repositories.watch(shop, "other-lib");
        synchronizer.defaultBranchPushed(shop, "ignored-sha");
        assertThat(repositories.watches(shop)).containsExactlyInAnyOrder("acme-sdk", "acme-api");
        assertThat(repositories.find(shop).orElseThrow().lastIndexedSha()).hasSize(40);
        assertThat(temp.resolve("work/sync-" + shop)).doesNotExist();
    }

    @Test
    void dependenciesAloneAreEnoughToStartWatching() throws Exception {
        seed(null);
        github.addRepository(1, 100, "acme/shop", "main", remoteUrl);
        UUID shop = repositories.upsertRepository(installation.id(), 100, "acme/shop", "main").id();
        synchronizer.defaultBranchPushed(shop, "sha");
        assertThat(repositories.watches(shop)).containsExactly("acme-sdk");
    }

    @Test
    void anUnreadableConfigurationKeepsTheCurrentWatches() throws Exception {
        seed("watch: [this is: not valid\n");
        github.addRepository(1, 100, "acme/shop", "main", remoteUrl);
        UUID shop = repositories.upsertRepository(installation.id(), 100, "acme/shop", "main").id();
        repositories.watch(shop, "acme-api");
        synchronizer.defaultBranchPushed(shop, "sha");
        assertThat(repositories.watches(shop)).containsExactly("acme-api");
    }

    @Test
    void refreshingReportsUnknownSourcesWithoutWatchingThem() throws Exception {
        Path checkout = temp.resolve("checkout");
        Files.createDirectories(checkout);
        Files.writeString(checkout.resolve("pom.xml"), POM);
        Files.writeString(checkout.resolve(".rook.yml"), "version: 1\nwatch:\n  - source: ghost\n");
        UUID shop = repositories.upsertRepository(installation.id(), 100, "acme/shop", "main").id();
        RepositorySynchronizer.WatchResult result = synchronizer.refreshWatches(shop, checkout);
        assertThat(result.configValid()).isTrue();
        assertThat(result.unknownSources()).containsExactly("ghost");
        assertThat(result.watching()).containsExactly("acme-sdk");
    }

    @Test
    void aPushWebhookTravelsAllTheWayToTheWatchList() throws Exception {
        seed("version: 1\nwatch:\n  - source: acme-api\n");
        github.addRepository(1, 100, "acme/shop", "main", remoteUrl);
        UUID shop = repositories.upsertRepository(installation.id(), 100, "acme/shop", "main").id();
        WebhookProcessor processor =
                new WebhookProcessor(
                        repositories,
                        new DeliveryLog(jdbc, clock),
                        new PullRequests(jdbc, clock),
                        synchronizer);
        Map<String, Object> payload =
                Map.of(
                        "ref",
                        "refs/heads/main",
                        "after",
                        "abc123",
                        "repository",
                        Map.of("id", 100, "full_name", "acme/shop", "default_branch", "main"));
        WebhookProcessor.Outcome outcome =
                processor.process(
                        "push-1", "push", Json.write(payload).getBytes(StandardCharsets.UTF_8));
        assertThat(outcome).isEqualTo(WebhookProcessor.Outcome.HANDLED);
        assertThat(repositories.watches(shop)).containsExactlyInAnyOrder("acme-sdk", "acme-api");
        assertThat(github.requests()).isNotEmpty();
    }
}
