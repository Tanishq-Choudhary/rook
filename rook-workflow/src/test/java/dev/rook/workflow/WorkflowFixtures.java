package dev.rook.workflow;

import dev.rook.core.id.Ids;
import dev.rook.testkit.TestClock;
import dev.rook.testkit.TestDatabases;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class WorkflowFixtures {

    final DataSource dataSource;
    final JdbcClient jdbc;
    final TransactionTemplate tx;
    final TestClock clock = TestClock.at("2026-08-16T10:00:00Z");
    final JobRepository jobs;
    private final UUID installation = Ids.newId();
    private int counter;

    WorkflowFixtures() {
        dataSource = TestDatabases.fresh();
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jobs = new JobRepository(jdbc, tx, clock);
        jdbc.sql(
                        "insert into watched_source (id, kind, display_name, location) values ('src', 'OPENAPI', 'src', 'http://x')")
                .update();
        jdbc.sql("insert into installation (id, account_login) values (:id, 'acme')")
                .param("id", installation)
                .update();
    }

    UUID repository() {
        UUID id = Ids.newId();
        jdbc.sql("insert into repository (id, installation_id, full_name) values (:id, :i, :name)")
                .param("id", id)
                .param("i", installation)
                .param("name", "acme/repo-" + (++counter))
                .update();
        return id;
    }

    UUID changeSet() {
        UUID id = Ids.newId();
        jdbc.sql(
                        """
                        insert into change_set (id, source_id, change_key, from_version, to_version,
                          highest_severity, requires_migration, detected_at)
                        values (:id, 'src', :key, '1', '2', 'BREAKING', true, now())
                        """)
                .param("id", id)
                .param("key", "key-" + (++counter))
                .update();
        return id;
    }

    UUID job(UUID repository) {
        return jobs.create(changeSet(), repository, 0, 5).jobId();
    }

    UUID job(UUID repository, int priority) {
        return jobs.create(changeSet(), repository, priority, 5).jobId();
    }

    int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
