package dev.rook.control.store;

import dev.rook.core.id.Ids;
import dev.rook.testkit.TestClock;
import dev.rook.testkit.TestDatabases;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class ControlFixtures {

    public final DataSource dataSource;
    public final JdbcClient jdbc;
    public final TransactionTemplate tx;
    public final TestClock clock;
    private int counter;

    public ControlFixtures() {
        dataSource = TestDatabases.fresh();
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        clock = TestClock.at("2026-10-01T10:00:00Z");
        jdbc.sql(
                        "insert into watched_source (id, kind, display_name, location)"
                                + " values ('src', 'OPENAPI', 'src', 'http://x')")
                .update();
    }

    public UUID installation() {
        UUID id = Ids.newId();
        jdbc.sql("insert into installation (id, account_login) values (:id, :login)")
                .param("id", id)
                .param("login", "acme-" + (++counter))
                .update();
        return id;
    }

    public UUID repository(UUID installation) {
        UUID id = Ids.newId();
        jdbc.sql("insert into repository (id, installation_id, full_name) values (:id, :i, :name)")
                .param("id", id)
                .param("i", installation)
                .param("name", "acme/repo-" + (++counter))
                .update();
        return id;
    }

    public UUID job(UUID repository) {
        UUID changeSet = Ids.newId();
        jdbc.sql(
                        """
                        insert into change_set (id, source_id, change_key, from_version, to_version,
                          highest_severity, requires_migration, detected_at)
                        values (:id, 'src', :key, '1', '2', 'BREAKING', true, now())
                        """)
                .param("id", changeSet)
                .param("key", "key-" + (++counter))
                .update();
        UUID id = Ids.newId();
        jdbc.sql(
                        """
                        insert into migration_job (id, change_set_id, repository_id, state)
                        values (:id, :changeSet, :repository, 'AWAITING_APPROVAL')
                        """)
                .param("id", id)
                .param("changeSet", changeSet)
                .param("repository", repository)
                .update();
        return id;
    }
}
