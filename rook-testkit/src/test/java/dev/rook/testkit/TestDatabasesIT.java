package dev.rook.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class TestDatabasesIT {

    @Test
    void freshDatabasesAreMigrated() {
        DataSource ds = TestDatabases.fresh();
        Integer count =
                JdbcClient.create(ds)
                        .sql(
                                "select count(*) from information_schema.tables where table_name = 'outbox'")
                        .query(Integer.class)
                        .single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void freshDatabasesDoNotShareRows() {
        JdbcClient first = JdbcClient.create(TestDatabases.fresh());
        JdbcClient second = JdbcClient.create(TestDatabases.fresh());
        first.sql("insert into outbox (topic, message_key, payload) values ('t', 'k', '{}'::jsonb)")
                .update();
        Integer inSecond = second.sql("select count(*) from outbox").query(Integer.class).single();
        assertThat(inSecond).isZero();
    }

    @Test
    void unmigratedDatabasesStartEmpty() {
        Integer count =
                JdbcClient.create(TestDatabases.unmigrated())
                        .sql(
                                "select count(*) from information_schema.tables where table_schema = 'public'")
                        .query(Integer.class)
                        .single();
        assertThat(count).isZero();
    }
}
