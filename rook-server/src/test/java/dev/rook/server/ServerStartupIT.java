package dev.rook.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@AutoConfigureMetrics
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rook.workflow.enabled=false")
@Testcontainers
class ServerStartupIT {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired JdbcClient jdbc;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    void healthReportsUpWithDatabase() {
        String body = client().get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessAndReadinessProbesAreExposed() {
        assertThat(client().get().uri("/actuator/health/liveness").retrieve().body(String.class))
                .contains("UP");
        assertThat(client().get().uri("/actuator/health/readiness").retrieve().body(String.class))
                .contains("UP");
    }

    @Test
    void prometheusEndpointCarriesApplicationTags() {
        String body = client().get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(body).contains("jvm_memory_used_bytes").contains("application=\"rook\"");
    }

    @Test
    void flywayAppliedTheSchemaOnStartup() {
        Integer tables =
                jdbc.sql(
                                "select count(*) from information_schema.tables where table_name in ('change_set','change_event','outbox')")
                        .query(Integer.class)
                        .single();
        assertThat(tables).isEqualTo(3);
    }
}
