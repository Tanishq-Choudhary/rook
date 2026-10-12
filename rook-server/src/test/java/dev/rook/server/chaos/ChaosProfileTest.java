package dev.rook.server.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class ChaosProfileTest {

    @Test
    void theControllerExistsOnlyUnderTheChaosProfile() {
        Profile profile = ChaosController.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("chaos");
    }
}
