package dev.rook.workflow.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.error.RookException;
import dev.rook.testkit.TestDatabases;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RepositoryStoreIT {

    private JdbcClient jdbc;
    private RepositoryStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = TestDatabases.fresh();
        jdbc = JdbcClient.create(ds);
        store =
                new RepositoryStore(
                        jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        for (String id : List.of("acme-sdk", "acme-api", "other")) {
            jdbc.sql(
                            "insert into watched_source (id, kind, display_name, location) values (:id, 'OPENAPI', :id, 'http://x')")
                    .param("id", id)
                    .update();
        }
    }

    @Test
    void installationsAreUpsertedByTheirGithubId() {
        RepositoryStore.Installation first = store.upsertInstallation(1001, "acme");
        RepositoryStore.Installation again = store.upsertInstallation(1001, "acme-renamed");
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.accountLogin()).isEqualTo("acme-renamed");
        assertThat(again.status()).isEqualTo("ACTIVE");
        assertThat(store.findInstallation(1001)).isPresent();
        assertThat(store.findInstallation(9)).isEmpty();
    }

    @Test
    void anInstallationCanBeLookedUpByItsOwnId() {
        RepositoryStore.Installation stored = store.upsertInstallation(1002, "acme");
        assertThat(store.installation(stored.id())).contains(stored);
        assertThat(store.installation(UUID.randomUUID())).isEmpty();
    }

    @Test
    void reinstallingReactivatesASuspendedInstallation() {
        RepositoryStore.Installation installation = store.upsertInstallation(1002, "acme");
        store.setInstallationStatus(1002, "SUSPENDED");
        assertThat(store.findInstallation(1002).orElseThrow().status()).isEqualTo("SUSPENDED");
        store.upsertInstallation(1002, "acme");
        assertThat(store.findInstallation(1002).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(installation.githubInstallationId()).isEqualTo(1002L);
    }

    @Test
    void repositoriesAreUpsertedAndFollowRenames() {
        UUID installation = store.upsertInstallation(1, "acme").id();
        RepositoryStore.Repository first =
                store.upsertRepository(installation, 77, "acme/shop", "main");
        RepositoryStore.Repository renamed =
                store.upsertRepository(installation, 77, "acme/storefront", "trunk");
        assertThat(renamed.id()).isEqualTo(first.id());
        assertThat(renamed.fullName()).isEqualTo("acme/storefront");
        assertThat(renamed.defaultBranch()).isEqualTo("trunk");
        assertThat(store.list(installation)).hasSize(1);
        assertThat(store.findByFullName("acme/storefront")).isPresent();
        assertThat(store.findByFullName("acme/shop")).isEmpty();
    }

    @Test
    void disablingARepositoryHidesItFromLookupsButKeepsTheRow() {
        UUID installation = store.upsertInstallation(1, "acme").id();
        RepositoryStore.Repository repository =
                store.upsertRepository(installation, 77, "acme/shop", "main");
        store.disableRepository(77);
        assertThat(store.find(repository.id()).orElseThrow().enabled()).isFalse();
        assertThat(store.findByFullName("acme/shop")).isEmpty();
        store.upsertRepository(installation, 77, "acme/shop", "main");
        assertThat(store.find(repository.id()).orElseThrow().enabled()).isTrue();
    }

    @Test
    void watchesAreIdempotentAndReplaceable() {
        UUID installation = store.upsertInstallation(1, "acme").id();
        UUID repository = store.upsertRepository(installation, 1, "acme/a", "main").id();
        store.watch(repository, "acme-sdk");
        store.watch(repository, "acme-sdk");
        assertThat(store.watches(repository)).containsExactly("acme-sdk");
        store.replaceWatches(repository, Set.of("acme-api", "other"));
        assertThat(store.watches(repository)).containsExactly("acme-api", "other");
        store.unwatch(repository, "other");
        assertThat(store.watches(repository)).containsExactly("acme-api");
    }

    @Test
    void watchingAnUnknownSourceIsAnInvalidRequest() {
        UUID installation = store.upsertInstallation(1, "acme").id();
        UUID repository = store.upsertRepository(installation, 1, "acme/a", "main").id();
        assertThatThrownBy(() -> store.watch(repository, "no-such-source"))
                .isInstanceOf(RookException.class);
    }

    @Test
    void onlyEnabledRepositoriesOfActiveInstallationsWatchSources() {
        UUID active = store.upsertInstallation(1, "acme").id();
        UUID suspended = store.upsertInstallation(2, "gone").id();
        UUID a = store.upsertRepository(active, 1, "acme/a", "main").id();
        UUID b = store.upsertRepository(active, 2, "acme/b", "main").id();
        UUID c = store.upsertRepository(suspended, 3, "gone/c", "main").id();
        for (UUID repository : List.of(a, b, c)) {
            store.watch(repository, "acme-sdk");
        }
        store.disableRepository(2);
        store.setInstallationStatus(2, "SUSPENDED");
        assertThat(store.watching("acme-sdk"))
                .extracting(RepositoryStore.Repository::fullName)
                .containsExactly("acme/a");
        assertThat(store.watching("other")).isEmpty();
    }

    @Test
    void indexedShaIsRemembered() {
        UUID installation = store.upsertInstallation(1, "acme").id();
        RepositoryStore.Repository repository =
                store.upsertRepository(installation, 1, "acme/a", "main");
        assertThat(repository.lastIndexedSha()).isNull();
        store.markIndexed(repository.id(), "abc123");
        assertThat(store.find(repository.id()).orElseThrow().lastIndexedSha()).isEqualTo("abc123");
    }
}
