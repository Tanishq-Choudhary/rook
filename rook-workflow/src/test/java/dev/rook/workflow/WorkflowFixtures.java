package dev.rook.workflow;

import dev.rook.core.id.Ids;
import dev.rook.testkit.TestClock;
import dev.rook.testkit.TestDatabases;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class WorkflowFixtures {

    public final DataSource dataSource;
    public final JdbcClient jdbc;
    public final TransactionTemplate tx;
    public final Clock clock;
    public final JobRepository jobs;
    private final UUID installation = Ids.newId();
    private int counter;

    public WorkflowFixtures() {
        this(TestClock.at("2026-08-16T10:00:00Z"));
    }

    public static WorkflowFixtures live() {
        return new WorkflowFixtures(Clock.systemUTC());
    }

    public WorkflowFixtures(Clock clock) {
        this.clock = clock;
        dataSource = TestDatabases.fresh();
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jobs = new JobRepository(jdbc, tx, clock);
        source("src");
        jdbc.sql("insert into installation (id, account_login) values (:id, 'acme')")
                .param("id", installation)
                .update();
    }

    public TestClock testClock() {
        return (TestClock) clock;
    }

    public void source(String id) {
        jdbc.sql(
                        "insert into watched_source (id, kind, display_name, location) values (:id, 'OPENAPI', :id, 'http://x')")
                .param("id", id)
                .update();
    }

    public UUID repository() {
        UUID id = Ids.newId();
        jdbc.sql("insert into repository (id, installation_id, full_name) values (:id, :i, :name)")
                .param("id", id)
                .param("i", installation)
                .param("name", "acme/repo-" + (++counter))
                .update();
        return id;
    }

    public UUID changeSet() {
        return changeSet("src", true, "BREAKING");
    }

    public UUID changeSet(String source, boolean requiresMigration, String severity) {
        UUID id = Ids.newId();
        jdbc.sql(
                        """
                        insert into change_set (id, source_id, change_key, from_version, to_version,
                          highest_severity, requires_migration, detected_at)
                        values (:id, :source, :key, '1', '2', :severity, :migrate, now())
                        """)
                .param("id", id)
                .param("source", source)
                .param("key", "key-" + (++counter))
                .param("severity", severity)
                .param("migrate", requiresMigration)
                .update();
        return id;
    }

    public UUID job(UUID repository) {
        return jobs.create(changeSet(), repository, 0, 5).jobId();
    }

    public UUID job(UUID repository, int priority) {
        return jobs.create(changeSet(), repository, priority, 5).jobId();
    }

    public int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
