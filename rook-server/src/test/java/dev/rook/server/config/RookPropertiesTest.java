package dev.rook.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RookPropertiesTest {

    @Test
    void roleDefaultsToAll() {
        RookProperties properties = new RookProperties(null);
        assertThat(properties.role()).isEqualTo(RookProperties.Role.ALL);
        assertThat(properties.runsApi()).isTrue();
        assertThat(properties.runsWorker()).isTrue();
    }

    @Test
    void apiRoleDoesNotRunWorkers() {
        RookProperties properties = new RookProperties(RookProperties.Role.API);
        assertThat(properties.runsApi()).isTrue();
        assertThat(properties.runsWorker()).isFalse();
    }

    @Test
    void workerRoleDoesNotServeApi() {
        RookProperties properties = new RookProperties(RookProperties.Role.WORKER);
        assertThat(properties.runsApi()).isFalse();
        assertThat(properties.runsWorker()).isTrue();
    }
}
