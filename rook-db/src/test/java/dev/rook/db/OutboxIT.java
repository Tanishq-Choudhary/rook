package dev.rook.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class OutboxIT {

    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Migrations.migrate(ds);
        jdbc = JdbcClient.create(ds);
    }

    @Test
    void appendReturnsAscendingIdsAndStoresPayloadAndHeaders() {
        long first =
                Outbox.append(jdbc, "topic-a", "key-1", "{\"n\":1}", Map.of("type", "created"));
        long second = Outbox.append(jdbc, "topic-a", "key-1", "{\"n\":2}", Map.of());
        assertThat(second).isGreaterThan(first);
        String payload =
                jdbc.sql("select payload::text from outbox where id = :id")
                        .param("id", first)
                        .query(String.class)
                        .single();
        assertThat(payload).contains("\"n\": 1");
        String headers =
                jdbc.sql("select headers::text from outbox where id = :id")
                        .param("id", first)
                        .query(String.class)
                        .single();
        assertThat(headers).contains("created");
    }

    @Test
    void newMessagesStartUnpublishedWithNoAttempts() {
        long id = Outbox.append(jdbc, "topic-b", "key", "{}", Map.of());
        Map<String, Object> row =
                jdbc.sql("select published_at, attempts from outbox where id = :id")
                        .param("id", id)
                        .query()
                        .singleRow();
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("attempts")).isEqualTo(0);
    }
}
