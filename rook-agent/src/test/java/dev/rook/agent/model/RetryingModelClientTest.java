package dev.rook.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.agent.model.http.ApiKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class RetryingModelClientTest {

    private static final ModelRequest REQUEST =
            ModelRequest.of("s", List.of(ModelMessage.user("x")), 10);

    private final List<Duration> slept = new ArrayList<>();

    private RetryingModelClient retrying(ModelClient delegate, int attempts) {
        return new RetryingModelClient(
                delegate,
                attempts,
                Duration.ofMillis(100),
                Duration.ofMillis(250),
                slept::add,
                RandomGenerator.of("L64X128MixRandom"));
    }

    private static ModelClient failing(AtomicInteger calls, int failures, boolean retryable) {
        return new ModelClient() {
            @Override
            public String modelId() {
                return "flaky";
            }

            @Override
            public ModelResponse complete(ModelRequest request) {
                if (calls.incrementAndGet() <= failures) {
                    throw new ModelException("unavailable", retryable);
                }
                return ScriptedModelClient.text("ok");
            }
        };
    }

    @Test
    void aRetryableFailureIsRetriedUntilItSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        RetryingModelClient client = retrying(failing(calls, 2, true), 4);
        assertThat(client.complete(REQUEST).text()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
        assertThat(slept).hasSize(2);
        assertThat(client.modelId()).isEqualTo("flaky");
    }

    @Test
    void nonRetryableFailuresSurfaceImmediately() {
        AtomicInteger calls = new AtomicInteger();
        RetryingModelClient client = retrying(failing(calls, 5, false), 4);
        assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(ModelException.class);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @Test
    void givingUpReportsTheAttemptCountAndStopsRetrying() {
        AtomicInteger calls = new AtomicInteger();
        RetryingModelClient client = retrying(failing(calls, 10, true), 3);
        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOfSatisfying(
                        ModelException.class,
                        e -> {
                            assertThat(e.retryable()).isFalse();
                            assertThat(e.getMessage()).contains("3 attempts");
                        });
        assertThat(calls.get()).isEqualTo(3);
        assertThat(slept).hasSize(2);
    }

    @Test
    void delaysGrowButStayWithinTheCapAndKeepHalfFixed() {
        RetryingModelClient client = retrying(failing(new AtomicInteger(), 0, true), 5);
        for (int attempt = 1; attempt <= 6; attempt++) {
            long ceiling = Math.min(100L << (attempt - 1), 250);
            for (int i = 0; i < 50; i++) {
                long millis = client.delay(attempt).toMillis();
                assertThat(millis).isBetween(ceiling / 2, ceiling);
            }
        }
    }

    @Test
    void anInterruptedWaitStopsTheRetries() {
        RetryingModelClient client =
                new RetryingModelClient(
                        failing(new AtomicInteger(), 5, true),
                        3,
                        Duration.ofMillis(10),
                        Duration.ofMillis(10),
                        d -> {
                            throw new InterruptedException();
                        },
                        RandomGenerator.getDefault());
        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("interrupted");
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void atLeastOneAttemptIsRequired() {
        assertThatThrownBy(() -> retrying(failing(new AtomicInteger(), 0, true), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void apiKeysNeverPrintTheirValue() {
        assertThat(new ApiKey("sk-very-secret").toString())
                .doesNotContain("sk-very-secret")
                .contains("redacted");
        assertThatThrownBy(() -> new ApiKey(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiKey(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
