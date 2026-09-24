package dev.rook.github.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.github.auth.AppJwt;
import dev.rook.github.auth.InstallationTokens;
import dev.rook.testkit.FakeGitHub;
import dev.rook.testkit.TestClock;
import java.net.http.HttpClient;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubClientTest {

    private static KeyPair pair;

    private final TestClock clock = TestClock.at("2026-10-01T10:00:00Z");
    private final List<Long> sleeps = new ArrayList<>();
    private FakeGitHub github;
    private GitHubClient client;
    private InstallationTokens tokens;

    @BeforeAll
    static void generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        github = new FakeGitHub(clock);
        github.addRepository(1, 100, "acme/shop", "main", "file:///repos/shop.git");
        github.addRepository(2, 200, "other/app", "trunk", "file:///repos/app.git");
        client = newClient(github.baseUrl(), Duration.ofSeconds(30));
    }

    private GitHubClient newClient(String baseUrl, Duration maxWait) {
        GitHubClient[] holder = new GitHubClient[1];
        InstallationTokens.Issuer issuer = (jwt, id) -> holder[0].issue(jwt, id);
        tokens = new InstallationTokens(issuer, new AppJwt(9, pair.getPrivate(), clock), clock);
        holder[0] =
                new GitHubClient(
                        baseUrl, HttpClient.newHttpClient(), tokens, sleeps::add, clock, maxWait);
        return holder[0];
    }

    @AfterEach
    void tearDown() {
        github.close();
    }

    @Test
    void anInstallationTokenIsFetchedOnceAndReused() {
        client.repository(1, "acme/shop");
        client.repository(1, "acme/shop");
        client.installationRepositories(1);
        assertThat(github.tokensIssued()).isEqualTo(1);
    }

    @Test
    void repositoriesArePagedAndScopedToTheInstallation() {
        for (int i = 0; i < 249; i++) {
            github.addRepository(1, 1000 + i, "acme/r" + i, "main", "file:///r" + i);
        }
        List<GitHubClient.Repo> repos = client.installationRepositories(1);
        assertThat(repos).hasSize(250);
        assertThat(repos.get(0))
                .isEqualTo(
                        new GitHubClient.Repo(100, "acme/shop", "main", "file:///repos/shop.git"));
        assertThat(client.installationRepositories(2)).hasSize(1);
        long pages =
                github.requests().stream()
                        .filter(r -> r.path().equals("/installation/repositories"))
                        .filter(r -> r.query().contains("per_page=100"))
                        .count();
        assertThat(pages).isEqualTo(4);
    }

    @Test
    void aRepositoryOfAnotherInstallationIsNotFoundAndNotRetried() {
        assertThatThrownBy(() -> client.repository(1, "other/app"))
                .isInstanceOf(GitHubException.class)
                .satisfies(e -> assertThat(((GitHubException) e).status()).isEqualTo(404))
                .hasMessageContaining("Not Found");
        assertThat(
                        github.requests().stream()
                                .filter(r -> r.path().equals("/repos/other/app"))
                                .count())
                .isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void pullRequestsAreCreatedFoundAndNeverDuplicated() {
        assertThat(client.findOpenPullRequest(1, "acme/shop", "rook/fix")).isEmpty();
        GitHubClient.PullRequest created =
                client.createPullRequest(1, "acme/shop", "Fix", "Body", "rook/fix", "main");
        assertThat(created.number()).isEqualTo(1);
        assertThat(created.url()).isEqualTo("https://github.test/acme/shop/pull/1");
        assertThat(created.state()).isEqualTo("open");
        Optional<GitHubClient.PullRequest> found =
                client.findOpenPullRequest(1, "acme/shop", "rook/fix");
        assertThat(found).isPresent();
        assertThat(found.get().number()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                client.createPullRequest(
                                        1, "acme/shop", "Again", "Body", "rook/fix", "main"))
                .isInstanceOf(GitHubException.class)
                .satisfies(e -> assertThat(((GitHubException) e).alreadyExists()).isTrue());
        assertThat(github.pulls()).hasSize(1);
        assertThat(github.pulls().get(0).base()).isEqualTo("main");
    }

    @Test
    void checkRunsAndCommentsReachTheRepository() {
        client.createCheckRun(1, "acme/shop", "abc123", "rook", "success", "Verified", "all green");
        client.comment(1, "acme/shop", 7, "ready for review");
        assertThat(github.checkRuns())
                .containsExactly(
                        new FakeGitHub.CheckRun(
                                "acme/shop", "abc123", "rook", "success", "all green"));
        assertThat(github.comments())
                .containsExactly(new FakeGitHub.Comment("acme/shop", 7, "ready for review"));
    }

    @Test
    void anExpiredTokenIsReplacedOnceAndTheCallSucceeds() {
        client.repository(1, "acme/shop");
        github.expireTokens();
        assertThat(client.repository(1, "acme/shop").id()).isEqualTo(100);
        assertThat(github.tokensIssued()).isEqualTo(2);
    }

    @Test
    void aRateLimitedCallWaitsForRetryAfterAndThenSucceeds() {
        client.repository(1, "acme/shop");
        github.failNext("/repos/acme/shop", 429, Map.of("Retry-After", "2"));
        assertThat(client.repository(1, "acme/shop").id()).isEqualTo(100);
        assertThat(sleeps).containsExactly(2000L);
    }

    @Test
    void anExhaustedPrimaryLimitWaitsUntilTheReset() {
        client.repository(1, "acme/shop");
        long reset = clock.instant().plusSeconds(12).getEpochSecond();
        github.failNext(
                "/repos/acme/shop",
                403,
                Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", Long.toString(reset)));
        assertThat(client.repository(1, "acme/shop").id()).isEqualTo(100);
        assertThat(sleeps).containsExactly(12_000L);
    }

    @Test
    void aWaitLongerThanTheCeilingIsReportedInsteadOfSlept() {
        GitHubClient impatient = newClient(github.baseUrl(), Duration.ofSeconds(5));
        impatient.repository(1, "acme/shop");
        github.failNext("/repos/acme/shop", 429, Map.of("Retry-After", "120"));
        assertThatThrownBy(() -> impatient.repository(1, "acme/shop"))
                .isInstanceOf(GitHubException.class)
                .satisfies(
                        e -> {
                            GitHubException failure = (GitHubException) e;
                            assertThat(failure.status()).isEqualTo(429);
                            assertThat(failure.retryAfterMillis()).isEqualTo(120_000);
                        });
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aForbiddenCallThatIsNotRateLimitedFailsAtOnce() {
        client.repository(1, "acme/shop");
        github.failNext("/repos/acme/shop", 403, Map.of());
        assertThatThrownBy(() -> client.repository(1, "acme/shop"))
                .isInstanceOf(GitHubException.class)
                .satisfies(e -> assertThat(((GitHubException) e).status()).isEqualTo(403));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void serverErrorsAreRetriedWithGrowingPauses() {
        client.repository(1, "acme/shop");
        github.failNext("/repos/acme/shop", 502, Map.of());
        github.failNext("/repos/acme/shop", 503, Map.of());
        assertThat(client.repository(1, "acme/shop").id()).isEqualTo(100);
        assertThat(sleeps).containsExactly(250L, 500L);
    }

    @Test
    void aServerThatKeepsFailingIsGivenUpOn() {
        client.repository(1, "acme/shop");
        for (int i = 0; i < 6; i++) {
            github.failNext("/repos/acme/shop", 500, Map.of());
        }
        assertThatThrownBy(() -> client.repository(1, "acme/shop"))
                .isInstanceOf(GitHubException.class)
                .satisfies(e -> assertThat(((GitHubException) e).status()).isEqualTo(500));
        assertThat(sleeps).hasSize(3);
    }

    @Test
    void anUnreachableServerBecomesAServiceUnavailableError() {
        github.close();
        assertThatThrownBy(() -> client.repository(1, "acme/shop"))
                .isInstanceOf(RuntimeException.class);
        GitHubClient dead = newClient("http://127.0.0.1:1", Duration.ofSeconds(1));
        assertThatThrownBy(() -> dead.installationRepositories(1))
                .isInstanceOf(GitHubException.class)
                .satisfies(e -> assertThat(((GitHubException) e).status()).isEqualTo(503));
    }
}
