package dev.rook.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import dev.rook.testkit.TestClock;
import dev.rook.testkit.TestDatabases;
import dev.rook.workflow.repo.RepositoryStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WebhookProcessorIT {

    private record Push(UUID repository, String sha) {}

    private JdbcClient jdbc;
    private TestClock clock;
    private RepositoryStore repositories;
    private PullRequests pullRequests;
    private DeliveryLog deliveries;
    private final List<Push> pushes = new ArrayList<>();
    private boolean failNextPush;
    private WebhookProcessor processor;

    @BeforeEach
    void setUp() {
        DataSource ds = TestDatabases.fresh();
        jdbc = JdbcClient.create(ds);
        clock = TestClock.at("2026-10-01T10:00:00Z");
        repositories =
                new RepositoryStore(
                        jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        pullRequests = new PullRequests(jdbc, clock);
        deliveries = new DeliveryLog(jdbc, clock);
        processor =
                new WebhookProcessor(
                        repositories,
                        deliveries,
                        pullRequests,
                        (repository, sha) -> {
                            if (failNextPush) {
                                failNextPush = false;
                                throw new IllegalStateException("queue unavailable");
                            }
                            pushes.add(new Push(repository, sha));
                        });
    }

    private WebhookProcessor.Outcome deliver(String id, String event, Map<String, Object> payload) {
        return processor.process(id, event, Json.write(payload).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> repo(long id, String fullName) {
        return Map.of("id", id, "full_name", fullName);
    }

    private Map<String, Object> installationCreated() {
        return Map.of(
                "action",
                "created",
                "installation",
                Map.of("id", 5001, "account", Map.of("login", "acme")),
                "repositories",
                List.of(repo(11, "acme/shop"), repo(12, "acme/blog")));
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    @Test
    void anInstallationCreatesTheAccountAndItsRepositories() {
        assertThat(deliver("d1", "installation", installationCreated()))
                .isEqualTo(WebhookProcessor.Outcome.HANDLED);
        RepositoryStore.Installation installation =
                repositories.findInstallation(5001).orElseThrow();
        assertThat(installation.accountLogin()).isEqualTo("acme");
        assertThat(installation.status()).isEqualTo("ACTIVE");
        assertThat(repositories.list(installation.id()))
                .extracting(RepositoryStore.Repository::fullName)
                .containsExactlyInAnyOrder("acme/shop", "acme/blog");
    }

    @Test
    void theSameDeliveryIsOnlyProcessedOnce() {
        deliver("d1", "installation", installationCreated());
        jdbc.sql("update repository set enabled = false").update();
        assertThat(deliver("d1", "installation", installationCreated()))
                .isEqualTo(WebhookProcessor.Outcome.DUPLICATE);
        assertThat(count("select count(*) from repository where enabled")).isZero();
        assertThat(count("select count(*) from webhook_delivery")).isEqualTo(1);
    }

    @Test
    void installationLifecycleChangesTheStatus() {
        deliver("d1", "installation", installationCreated());
        Map<String, Object> base =
                Map.of("installation", Map.of("id", 5001, "account", Map.of("login", "acme")));
        for (String[] step :
                new String[][] {
                    {"suspend", "SUSPENDED"}, {"unsuspend", "ACTIVE"}, {"deleted", "REMOVED"}
                }) {
            Map<String, Object> payload = new HashMap<>(base);
            payload.put("action", step[0]);
            assertThat(deliver("life-" + step[0], "installation", payload))
                    .isEqualTo(WebhookProcessor.Outcome.HANDLED);
            assertThat(repositories.findInstallation(5001).orElseThrow().status())
                    .isEqualTo(step[1]);
        }
    }

    @Test
    void unknownInstallationActionsAreIgnored() {
        Map<String, Object> payload =
                Map.of(
                        "action",
                        "new_permissions_accepted",
                        "installation",
                        Map.of("id", 5001, "account", Map.of("login", "acme")));
        assertThat(deliver("d1", "installation", payload))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(count("select count(*) from installation")).isZero();
    }

    @Test
    void addedAndRemovedRepositoriesAreFollowed() {
        deliver("d1", "installation", installationCreated());
        Map<String, Object> change =
                Map.of(
                        "action",
                        "added",
                        "installation",
                        Map.of("id", 5001, "account", Map.of("login", "acme")),
                        "repositories_added",
                        List.of(repo(13, "acme/new")),
                        "repositories_removed",
                        List.of(repo(12, "acme/blog")));
        assertThat(deliver("d2", "installation_repositories", change))
                .isEqualTo(WebhookProcessor.Outcome.HANDLED);
        UUID installation = repositories.findInstallation(5001).orElseThrow().id();
        assertThat(repositories.list(installation))
                .filteredOn(RepositoryStore.Repository::enabled)
                .extracting(RepositoryStore.Repository::fullName)
                .containsExactlyInAnyOrder("acme/shop", "acme/new");
    }

    @Test
    void repositoriesAddedForAnUnknownInstallationCreateIt() {
        Map<String, Object> change =
                Map.of(
                        "action",
                        "added",
                        "installation",
                        Map.of("id", 7777, "account", Map.of("login", "late")),
                        "repositories_added",
                        List.of(repo(21, "late/app")));
        deliver("d1", "installation_repositories", change);
        assertThat(repositories.findInstallation(7777)).isPresent();
        assertThat(repositories.findByFullName("late/app")).isPresent();
    }

    private Map<String, Object> push(String ref, String after) {
        return Map.of(
                "ref",
                ref,
                "after",
                after,
                "repository",
                Map.of("id", 11, "full_name", "acme/shop", "default_branch", "main"));
    }

    @Test
    void aPushToTheDefaultBranchReachesTheListener() {
        deliver("d1", "installation", installationCreated());
        UUID shop = repositories.findByFullName("acme/shop").orElseThrow().id();
        assertThat(deliver("d2", "push", push("refs/heads/main", "abc123")))
                .isEqualTo(WebhookProcessor.Outcome.HANDLED);
        assertThat(pushes).containsExactly(new Push(shop, "abc123"));
    }

    @Test
    void otherPushesAreIgnored() {
        deliver("d1", "installation", installationCreated());
        assertThat(deliver("d2", "push", push("refs/heads/feature", "abc123")))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(deliver("d3", "push", push("refs/heads/main", "0".repeat(40))))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(deliver("d4", "push", push("refs/tags/v1", "abc")))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        repositories.disableRepository(11);
        assertThat(deliver("d5", "push", push("refs/heads/main", "def456")))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(pushes).isEmpty();
    }

    @Test
    void aPushForARepositoryWeNeverHeardOfIsIgnored() {
        assertThat(deliver("d1", "push", push("refs/heads/main", "abc")))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
    }

    private Map<String, Object> pullRequestEvent(String action, boolean merged) {
        return Map.of(
                "action",
                action,
                "pull_request",
                Map.of("number", 9, "merged", merged),
                "repository",
                Map.of("id", 11, "full_name", "acme/shop"));
    }

    private void openPullRequest() {
        deliver("setup", "installation", installationCreated());
        UUID shop = repositories.findByFullName("acme/shop").orElseThrow().id();
        pullRequests.record(
                null, shop, 9, "http://git.test/acme/shop/pull/9", "rook/fix-1", "sha1");
    }

    @Test
    void aMergedPullRequestIsMarkedMerged() {
        openPullRequest();
        assertThat(deliver("d1", "pull_request", pullRequestEvent("closed", true)))
                .isEqualTo(WebhookProcessor.Outcome.HANDLED);
        UUID shop = repositories.findByFullName("acme/shop").orElseThrow().id();
        PullRequests.Record record = pullRequests.findByBranch(shop, "rook/fix-1").orElseThrow();
        assertThat(record.state()).isEqualTo(PullRequests.State.MERGED);
        assertThat(record.closedAt()).isEqualTo(clock.instant());
    }

    @Test
    void aClosedPullRequestWithoutMergeIsMarkedClosed() {
        openPullRequest();
        deliver("d1", "pull_request", pullRequestEvent("closed", false));
        UUID shop = repositories.findByFullName("acme/shop").orElseThrow().id();
        assertThat(pullRequests.findByBranch(shop, "rook/fix-1").orElseThrow().state())
                .isEqualTo(PullRequests.State.CLOSED);
    }

    @Test
    void otherPullRequestActionsAndUnknownPullRequestsAreIgnored() {
        openPullRequest();
        assertThat(deliver("d1", "pull_request", pullRequestEvent("synchronize", false)))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        Map<String, Object> unknown =
                Map.of(
                        "action",
                        "closed",
                        "pull_request",
                        Map.of("number", 404, "merged", true),
                        "repository",
                        Map.of("id", 11, "full_name", "acme/shop"));
        assertThat(deliver("d2", "pull_request", unknown))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        UUID shop = repositories.findByFullName("acme/shop").orElseThrow().id();
        assertThat(pullRequests.findByBranch(shop, "rook/fix-1").orElseThrow().state())
                .isEqualTo(PullRequests.State.OPEN);
    }

    @Test
    void aFailedHandlerReleasesTheDeliveryForARedelivery() {
        deliver("d1", "installation", installationCreated());
        failNextPush = true;
        assertThatThrownBy(() -> deliver("d2", "push", push("refs/heads/main", "abc")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("select count(*) from webhook_delivery where id = 'd2'")).isZero();
        assertThat(deliver("d2", "push", push("refs/heads/main", "abc")))
                .isEqualTo(WebhookProcessor.Outcome.HANDLED);
        assertThat(pushes).hasSize(1);
    }

    @Test
    void unknownEventsAreRecordedAndIgnored() {
        assertThat(deliver("d1", "ping", Map.of("zen", "keep it logically awesome")))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(
                        jdbc.sql("select outcome from webhook_delivery where id = 'd1'")
                                .query(String.class)
                                .single())
                .isEqualTo("IGNORED");
    }

    @Test
    void malformedDeliveriesAreRejectedBeforeAnythingIsRecorded() {
        byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> processor.process(null, "push", ok))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(() -> processor.process("x".repeat(101), "push", ok))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(() -> processor.process("d1", " ", ok))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(
                        () ->
                                processor.process(
                                        "d1", "push", "not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(
                        () ->
                                processor.process(
                                        "d1", "push", "[1]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(() -> processor.process("d1", "installation", ok))
                .isInstanceOf(RookException.class);
        assertThat(count("select count(*) from webhook_delivery")).isZero();
    }

    @Test
    void oldDeliveryRecordsCanBePurged() {
        deliver("old", "ping", Map.of());
        clock.advance(Duration.ofDays(10));
        deliver("new", "ping", Map.of());
        assertThat(deliveries.purgeOlderThan(Duration.ofDays(7))).isEqualTo(1);
        assertThat(count("select count(*) from webhook_delivery")).isEqualTo(1);
    }

    @Test
    void recordingAPullRequestTwiceKeepsOneRowPerBranch() {
        deliver("setup", "installation", installationCreated());
        UUID shop = repositories.findByFullName("acme/shop").orElseThrow().id();
        PullRequests.Record first = pullRequests.record(null, shop, 9, "u1", "rook/fix", "sha1");
        PullRequests.Record second = pullRequests.record(null, shop, 9, "u1", "rook/fix", "sha2");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.headSha()).isEqualTo("sha2");
        assertThat(count("select count(*) from pull_request")).isEqualTo(1);
        assertThat(pullRequests.findByJob(UUID.randomUUID())).isEmpty();
    }
}
