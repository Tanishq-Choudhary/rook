package dev.rook.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class JobSchemaIT {

    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcClient jdbc;
    static UUID repository;
    static UUID otherRepository;
    static UUID changeSetOne;
    static UUID changeSetTwo;

    @BeforeAll
    static void migrate() {
        DataSource ds =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Migrations.migrate(ds);
        jdbc = JdbcClient.create(ds);
        jdbc.sql(
                        "insert into watched_source (id, kind, display_name, location) values ('src', 'OPENAPI', 'src', 'http://x')")
                .update();
        UUID installation = UUID.randomUUID();
        jdbc.sql("insert into installation (id, account_login) values (:id, 'acme')")
                .param("id", installation)
                .update();
        repository = repository(installation, "acme/one");
        otherRepository = repository(installation, "acme/two");
        changeSetOne = changeSet("key-1");
        changeSetTwo = changeSet("key-2");
    }

    private static UUID repository(UUID installation, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into repository (id, installation_id, full_name) values (:id, :i, :n)")
                .param("id", id)
                .param("i", installation)
                .param("n", name)
                .update();
        return id;
    }

    private static UUID changeSet(String key) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                        insert into change_set (id, source_id, change_key, from_version, to_version,
                          highest_severity, requires_migration, detected_at)
                        values (:id, 'src', :key, '1', '2', 'BREAKING', true, now())
                        """)
                .param("id", id)
                .param("key", key)
                .update();
        return id;
    }

    private static UUID job(UUID changeSet, UUID repo, String state) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "insert into migration_job (id, change_set_id, repository_id, state) values (:id, :cs, :repo, :state)")
                .param("id", id)
                .param("cs", changeSet)
                .param("repo", repo)
                .param("state", state)
                .update();
        return id;
    }

    @Test
    void aChangeSetProducesAtMostOneJobPerRepository() {
        UUID cs = changeSet("dup-key");
        job(cs, repository, "PENDING");
        assertThatThrownBy(() -> job(cs, repository, "PENDING"))
                .isInstanceOf(DuplicateKeyException.class);
        job(cs, otherRepository, "PENDING");
    }

    @Test
    void onlyOneJobPerRepositoryMayBeRunning() {
        UUID repo =
                repository(
                        jdbc.sql("select id from installation limit 1").query(UUID.class).single(),
                        "acme/running");
        job(changeSetOne, repo, "INDEXING");
        assertThatThrownBy(() -> job(changeSetTwo, repo, "PROPOSING"))
                .isInstanceOf(DuplicateKeyException.class);
        job(changeSet("waiting-key"), repo, "PENDING");
        job(changeSet("approval-key"), repo, "AWAITING_APPROVAL");
        job(changeSet("done-key"), repo, "COMPLETED");
    }

    @Test
    void unknownStatesAreRejected() {
        assertThatThrownBy(() -> job(changeSet("bad-state"), repository, "SLEEPING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leaseOwnerAndExpiryComeTogether() {
        UUID id = job(changeSet("lease-key"), otherRepository, "PENDING");
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "update migration_job set lease_owner = 'w1' where id = :id")
                                        .param("id", id)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql(
                        "update migration_job set lease_owner = 'w1', lease_expires_at = now() where id = :id")
                .param("id", id)
                .update();
    }

    @Test
    void deletingARepositoryCascadesToWatchesButNotToJobs() {
        UUID installation =
                jdbc.sql("select id from installation limit 1").query(UUID.class).single();
        UUID repo = repository(installation, "acme/cascade");
        jdbc.sql("insert into repository_watch (repository_id, source_id) values (:r, 'src')")
                .param("r", repo)
                .update();
        job(changeSet("cascade-key"), repo, "COMPLETED");
        assertThatThrownBy(
                        () ->
                                jdbc.sql("delete from repository where id = :id")
                                        .param("id", repo)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void jobEventsAreOrderedAndCascadeWithTheirJob() {
        UUID id = job(changeSet("event-key"), otherRepository, "COMPLETED");
        for (String kind : new String[] {"A", "B", "C"}) {
            jdbc.sql("insert into job_event (job_id, kind) values (:id, :kind)")
                    .param("id", id)
                    .param("kind", kind)
                    .update();
        }
        var kinds =
                jdbc.sql("select kind from job_event where job_id = :id order by id")
                        .param("id", id)
                        .query(String.class)
                        .list();
        assertThat(kinds).containsExactly("A", "B", "C");
        jdbc.sql("delete from migration_job where id = :id").param("id", id).update();
        assertThat(
                        jdbc.sql("select count(*) from job_event where job_id = :id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    @Test
    void deadLettersSurviveTheirJob() {
        UUID id = job(changeSet("dead-key"), otherRepository, "DEAD");
        UUID letter = UUID.randomUUID();
        jdbc.sql(
                        "insert into dead_letter (id, job_id, source, reason, payload) values (:id, :job, 'worker', 'exhausted', '{}'::jsonb)")
                .param("id", letter)
                .param("job", id)
                .update();
        jdbc.sql("delete from migration_job where id = :id").param("id", id).update();
        assertThat(
                        jdbc.sql("select job_id from dead_letter where id = :id")
                                .param("id", letter)
                                .query(UUID.class)
                                .optional())
                .isEmpty();
        assertThat(
                        jdbc.sql("select count(*) from dead_letter where id = :id")
                                .param("id", letter)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }
}
