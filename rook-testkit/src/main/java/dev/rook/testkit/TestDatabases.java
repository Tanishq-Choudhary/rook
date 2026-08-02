package dev.rook.testkit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.rook.db.Migrations;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class TestDatabases {

    private static final String TEMPLATE = "rook_template";
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static PostgreSQLContainer container;
    private static boolean templateReady;

    private TestDatabases() {}

    public static synchronized PostgreSQLContainer container() {
        if (container == null) {
            container =
                    new PostgreSQLContainer("postgres:16-alpine")
                            .withCommand(
                                    "postgres",
                                    "-c",
                                    "fsync=off",
                                    "-c",
                                    "synchronous_commit=off",
                                    "-c",
                                    "full_page_writes=off",
                                    "-c",
                                    "max_connections=300");
            container.start();
        }
        return container;
    }

    public static synchronized DataSource fresh() {
        PostgreSQLContainer postgres = container();
        try {
            if (!templateReady) {
                createDatabase(postgres, TEMPLATE, null);
                try (HikariDataSource template = pool(postgres, TEMPLATE, 2)) {
                    Migrations.migrate(template);
                }
                templateReady = true;
            }
            String name = "rook_test_" + COUNTER.incrementAndGet();
            createDatabase(postgres, name, TEMPLATE);
            return pool(postgres, name, 20);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static DataSource unmigrated() {
        PostgreSQLContainer postgres = container();
        String name = "rook_raw_" + COUNTER.incrementAndGet();
        try {
            createDatabase(postgres, name, null);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return pool(postgres, name, 5);
    }

    private static void createDatabase(PostgreSQLContainer postgres, String name, String template)
            throws SQLException {
        try (HikariDataSource admin = pool(postgres, postgres.getDatabaseName(), 1);
                Connection connection = admin.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create database " + name + (template == null ? "" : " template " + template));
        }
    }

    private static HikariDataSource pool(PostgreSQLContainer postgres, String database, int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(
                "jdbc:postgresql://"
                        + postgres.getHost()
                        + ":"
                        + postgres.getMappedPort(5432)
                        + "/"
                        + database);
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(0);
        return new HikariDataSource(config);
    }
}
