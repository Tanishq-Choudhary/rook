package dev.rook.control.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.control.store.ControlFixtures;
import dev.rook.core.id.Ids;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiKeysIT {

    private ControlFixtures fixtures;
    private ApiKeys keys;
    private UUID installation;

    @BeforeEach
    void setUp() {
        fixtures = new ControlFixtures();
        keys = new ApiKeys(fixtures.jdbc, fixtures.clock);
        installation = fixtures.installation();
    }

    @Test
    void anIssuedKeyAuthenticatesWithItsInstallationAndScopes() {
        ApiKeys.Issued issued = keys.issue(installation, "ci", EnumSet.of(Scope.READ, Scope.WRITE));
        assertThat(issued.key()).startsWith("rk_" + issued.prefix() + "_");
        Principal principal = keys.authenticate(issued.key()).orElseThrow();
        assertThat(principal.keyId()).isEqualTo(issued.id());
        assertThat(principal.installationId()).isEqualTo(installation);
        assertThat(principal.name()).isEqualTo("ci");
        assertThat(principal.scopes()).containsExactlyInAnyOrder(Scope.READ, Scope.WRITE);
        assertThat(principal.has(Scope.READ)).isTrue();
        assertThat(principal.has(Scope.ADMIN)).isFalse();
        assertThat(principal.global()).isFalse();
    }

    @Test
    void theSecretIsNeverStoredInTheClear() {
        ApiKeys.Issued issued = keys.issue(installation, "ci", EnumSet.of(Scope.READ));
        String secret = issued.key().substring(("rk_" + issued.prefix() + "_").length());
        String stored =
                fixtures.jdbc
                        .sql("select hash from api_key where id = :id")
                        .param("id", issued.id())
                        .query(String.class)
                        .single();
        assertThat(stored).hasSize(64).doesNotContain(secret);
        assertThat(
                        fixtures.jdbc
                                .sql(
                                        "select count(*) from api_key where hash like :s or prefix like :s or name like :s")
                                .param("s", "%" + secret + "%")
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    @Test
    void issuedKeysAreUniqueAndHighEntropy() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ApiKeys.Issued issued = keys.issue(installation, "k" + i, EnumSet.of(Scope.READ));
            assertThat(issued.key()).hasSize(57);
            assertThat(seen.add(issued.key())).isTrue();
        }
        assertThat(keys.list(installation)).hasSize(200);
    }

    @Test
    void wrongOrMalformedKeysAreRefused() {
        ApiKeys.Issued issued = keys.issue(installation, "ci", EnumSet.of(Scope.READ));
        String flipped =
                issued.key().substring(0, issued.key().length() - 1)
                        + (issued.key().endsWith("A") ? "B" : "A");
        assertThat(keys.authenticate(flipped)).isEmpty();
        assertThat(keys.authenticate("rk_" + issued.prefix() + "_" + "x".repeat(43))).isEmpty();
        assertThat(keys.authenticate("rk_unknown123_" + "x".repeat(43))).isEmpty();
        assertThat(keys.authenticate(null)).isEmpty();
        assertThat(keys.authenticate("")).isEmpty();
        assertThat(keys.authenticate("Bearer " + issued.key())).isEmpty();
        assertThat(keys.authenticate("rk_" + issued.prefix())).isEmpty();
        assertThat(keys.authenticate("'; drop table api_key; --")).isEmpty();
        assertThat(keys.authenticate("  " + issued.key() + "\n")).isPresent();
    }

    @Test
    void aRevokedKeyStopsWorkingAndRevokingTwiceIsHarmless() {
        ApiKeys.Issued issued = keys.issue(installation, "ci", EnumSet.of(Scope.READ));
        assertThat(keys.revoke(issued.id())).isTrue();
        assertThat(keys.revoke(issued.id())).isFalse();
        assertThat(keys.authenticate(issued.key())).isEmpty();
        assertThat(keys.list(installation))
                .singleElement()
                .satisfies(s -> assertThat(s.revoked()).isTrue());
    }

    @Test
    void scopesImplyTheOnesBelowThem() {
        assertThat(Scope.ADMIN.implies(Scope.READ)).isTrue();
        assertThat(Scope.WRITE.implies(Scope.READ)).isTrue();
        assertThat(Scope.READ.implies(Scope.WRITE)).isFalse();
        Principal writer = new Principal(Ids.newId(), installation, "w", EnumSet.of(Scope.WRITE));
        assertThat(writer.has(Scope.READ)).isTrue();
        assertThat(writer.has(Scope.ADMIN)).isFalse();
    }

    @Test
    void aKeyOnlyReachesItsOwnInstallationUnlessItIsGlobal() {
        UUID other = fixtures.installation();
        Principal scoped =
                keys.authenticate(keys.issue(installation, "a", EnumSet.of(Scope.READ)).key())
                        .orElseThrow();
        assertThat(scoped.canAccess(installation)).isTrue();
        assertThat(scoped.canAccess(other)).isFalse();
        Principal global =
                keys.authenticate(keys.issue(null, "root", EnumSet.of(Scope.ADMIN)).key())
                        .orElseThrow();
        assertThat(global.global()).isTrue();
        assertThat(global.canAccess(other)).isTrue();
    }

    @Test
    void usingAKeyRecordsWhenItWasLastUsed() {
        ApiKeys.Issued issued = keys.issue(installation, "ci", EnumSet.of(Scope.READ));
        assertThat(
                        fixtures.jdbc
                                .sql("select last_used_at is null from api_key where id = :id")
                                .param("id", issued.id())
                                .query(Boolean.class)
                                .single())
                .isTrue();
        keys.authenticate(issued.key());
        assertThat(
                        fixtures.jdbc
                                .sql("select last_used_at is not null from api_key where id = :id")
                                .param("id", issued.id())
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    private Timestamp lastUsed(UUID id) {
        return fixtures.jdbc
                .sql("select last_used_at from api_key where id = :id")
                .param("id", id)
                .query(Timestamp.class)
                .single();
    }

    @Test
    void theLastUsedTimeIsOnlyRewrittenOncePerMinute() {
        ApiKeys.Issued issued = keys.issue(installation, "ci", EnumSet.of(Scope.READ));
        keys.authenticate(issued.key());
        Timestamp first = lastUsed(issued.id());

        fixtures.clock.advance(Duration.ofSeconds(30));
        keys.authenticate(issued.key());
        assertThat(lastUsed(issued.id())).isEqualTo(first);

        fixtures.clock.advance(Duration.ofSeconds(31));
        keys.authenticate(issued.key());
        assertThat(lastUsed(issued.id())).isAfter(first);
    }

    @Test
    void manyAuthenticationsInParallelAllSucceed() throws Exception {
        ApiKeys.Issued issued = keys.issue(installation, "ci", EnumSet.of(Scope.READ));
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 200; i++) {
                futures.add(pool.submit(() -> keys.authenticate(issued.key()).isPresent()));
            }
            for (var future : futures) {
                assertThat(future.get()).isTrue();
            }
        }
    }

    @Test
    void theBootstrapKeyIsIdempotentAndAuthenticatesAsAGlobalAdmin() {
        String configured = "bootstrap-secret-with-plenty-of-characters-1234";
        Principal first = keys.bootstrap(configured).orElseThrow();
        Principal second = keys.bootstrap(configured).orElseThrow();
        assertThat(second.keyId()).isEqualTo(first.keyId());
        assertThat(first.global()).isTrue();
        assertThat(first.has(Scope.ADMIN)).isTrue();
        assertThat(keys.authenticate(configured)).isPresent();
        assertThat(keys.authenticate(configured + "x")).isEmpty();
        assertThat(keys.authenticate("short")).isEmpty();
        assertThat(
                        fixtures.jdbc
                                .sql("select count(*) from api_key where name = 'bootstrap'")
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        assertThat(
                        fixtures.jdbc
                                .sql("select count(*) from api_key where hash = :h")
                                .param("h", configured)
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    @Test
    void weakBootstrapKeysAreRefused() {
        assertThatThrownBy(() -> keys.bootstrap("too-short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.bootstrap(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuingNeedsANameScopesAndAdminForGlobalKeys() {
        assertThatThrownBy(() -> keys.issue(installation, " ", EnumSet.of(Scope.READ)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.issue(installation, "n", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.issue(null, "n", EnumSet.of(Scope.READ)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
