package dev.rook.db;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

public final class Migrations {

    public static final String LOCATION = "classpath:db/migration";

    private Migrations() {}

    public static MigrateResult migrate(DataSource dataSource) {
        return flyway(dataSource).migrate();
    }

    public static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();
    }
}
