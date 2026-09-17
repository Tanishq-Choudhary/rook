package dev.rook.control.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.control.policy.ApprovalMode;
import dev.rook.control.policy.Decision;
import dev.rook.control.policy.Policy;
import dev.rook.control.policy.Reason;
import dev.rook.core.id.Ids;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyStoreIT {

    private ControlFixtures fixtures;
    private PolicyStore store;
    private UUID installation;
    private UUID repository;

    @BeforeEach
    void setUp() {
        fixtures = new ControlFixtures();
        store = new PolicyStore(fixtures.jdbc, fixtures.tx);
        installation = fixtures.installation();
        repository = fixtures.repository(installation);
    }

    @Test
    void withoutAnyStoredPolicyTheStandardOneApplies() {
        PolicyStore.Stored effective = store.effective(installation, repository);
        assertThat(effective.policy()).isEqualTo(Policy.standard());
        assertThat(effective.version()).isZero();
        assertThat(effective.id()).isNull();
    }

    @Test
    void savingNewVersionsKeepsTheHistoryAndTheLatestWins() {
        PolicyStore.Stored first = store.save(installation, null, Policy.standard());
        PolicyStore.Stored second =
                store.save(installation, null, Policy.standard().withLimits(4, 100));
        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(store.latest(installation, null).orElseThrow().policy().maxFilesChanged())
                .isEqualTo(4);
        assertThat(store.find(first.id()).orElseThrow().policy()).isEqualTo(Policy.standard());
        assertThat(fixtures.jdbc.sql("select count(*) from policy").query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    void aRepositoryPolicyOverridesTheInstallationPolicy() {
        store.save(installation, null, Policy.standard().withApproval(ApprovalMode.ALWAYS));
        UUID other = fixtures.repository(installation);
        store.save(installation, repository, Policy.standard().withApproval(ApprovalMode.NEVER));
        assertThat(store.effective(installation, repository).policy().approval())
                .isEqualTo(ApprovalMode.NEVER);
        assertThat(store.effective(installation, other).policy().approval())
                .isEqualTo(ApprovalMode.ALWAYS);
        assertThat(store.effective(installation, null).policy().approval())
                .isEqualTo(ApprovalMode.ALWAYS);
    }

    @Test
    void policiesOfOneInstallationNeverApplyToAnother() {
        store.save(installation, null, Policy.standard().withLimits(1, 1));
        UUID stranger = fixtures.installation();
        assertThat(store.effective(stranger, null).policy()).isEqualTo(Policy.standard());
    }

    @Test
    void versionsAreNumberedPerScope() {
        store.save(installation, null, Policy.standard());
        PolicyStore.Stored scoped = store.save(installation, repository, Policy.standard());
        assertThat(scoped.version()).isEqualTo(1);
        assertThat(store.save(installation, repository, Policy.standard()).version()).isEqualTo(2);
    }

    @Test
    void anUnknownInstallationIsRejected() {
        assertThatThrownBy(() -> store.save(Ids.newId(), null, Policy.standard()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decisionsAreStoredWithTheJobAndTheLatestIsReturned() {
        UUID job = fixtures.job(repository);
        assertThat(store.latestDecision(job)).isEmpty();
        store.recordDecision(
                job,
                new Decision(
                        Decision.Outcome.REQUIRE_APPROVAL, List.of(new Reason("LARGE", "big"))),
                3);
        store.recordDecision(job, new Decision(Decision.Outcome.ALLOW, List.of()), 4);
        PolicyStore.StoredDecision latest = store.latestDecision(job).orElseThrow();
        assertThat(latest.decision().allowed()).isTrue();
        assertThat(latest.policyVersion()).isEqualTo(4);
        assertThat(
                        fixtures.jdbc
                                .sql("select count(*) from policy_decision where job_id = :j")
                                .param("j", job)
                                .query(Integer.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void decisionReasonsRoundTrip() {
        UUID job = fixtures.job(repository);
        Decision denied =
                new Decision(
                        Decision.Outcome.DENY,
                        List.of(
                                new Reason("SENSITIVE_PATH", ".env"),
                                new Reason("UNVERIFIED", "x")));
        store.recordDecision(job, denied, 1);
        assertThat(store.latestDecision(job).orElseThrow().decision()).isEqualTo(denied);
    }
}
