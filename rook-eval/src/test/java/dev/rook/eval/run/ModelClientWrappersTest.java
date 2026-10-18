package dev.rook.eval.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.agent.model.ModelClient;
import dev.rook.agent.model.ModelException;
import dev.rook.agent.model.ModelMessage;
import dev.rook.agent.model.ModelRequest;
import dev.rook.agent.model.ModelResponse;
import dev.rook.agent.model.StopReason;
import dev.rook.agent.model.Usage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ModelClientWrappersTest {

    private static final ModelRequest REQUEST =
            ModelRequest.of("system", List.of(ModelMessage.user("go")), 64);

    private static ModelClient answering() {
        return new ModelClient() {
            @Override
            public String modelId() {
                return "model-z";
            }

            @Override
            public ModelResponse complete(ModelRequest request) {
                return new ModelResponse(
                        "ok",
                        List.of(),
                        new Usage(1, 1, 0),
                        StopReason.END_TURN,
                        "model-z",
                        Duration.ZERO);
            }
        };
    }

    private static ModelClient failing(boolean retryable) {
        return new ModelClient() {
            @Override
            public String modelId() {
                return "model-z";
            }

            @Override
            public ModelResponse complete(ModelRequest request) {
                throw new ModelException("quota exhausted", retryable);
            }
        };
    }

    @Test
    void callsCloserThanTheGapWaitForTheRemainder() {
        AtomicLong clock = new AtomicLong(0);
        List<Long> pauses = new ArrayList<>();
        PacedModelClient paced =
                new PacedModelClient(
                        answering(),
                        Duration.ofMillis(100),
                        clock::get,
                        millis -> {
                            pauses.add(millis);
                            clock.addAndGet(millis * 1_000_000);
                        });

        paced.complete(REQUEST);
        paced.complete(REQUEST);
        clock.addAndGet(Duration.ofMillis(30).toNanos());
        paced.complete(REQUEST);

        assertThat(pauses).containsExactly(100L, 70L);
        assertThat(paced.modelId()).isEqualTo("model-z");
    }

    @Test
    void callsFartherApartThanTheGapDoNotWait() {
        AtomicLong clock = new AtomicLong(0);
        List<Long> pauses = new ArrayList<>();
        PacedModelClient paced =
                new PacedModelClient(answering(), Duration.ofMillis(100), clock::get, pauses::add);

        paced.complete(REQUEST);
        clock.addAndGet(Duration.ofMillis(500).toNanos());
        paced.complete(REQUEST);

        assertThat(pauses).isEmpty();
    }

    @Test
    void aZeroGapNeverWaits() {
        AtomicLong clock = new AtomicLong(0);
        List<Long> pauses = new ArrayList<>();
        PacedModelClient paced =
                new PacedModelClient(answering(), Duration.ZERO, clock::get, pauses::add);

        for (int i = 0; i < 5; i++) {
            paced.complete(REQUEST);
        }

        assertThat(pauses).isEmpty();
    }

    @Test
    void aWatchedClientRemembersAFailureOnceAndStillThrows() {
        WatchedModelClient watched = new WatchedModelClient(failing(false));

        assertThat(watched.takeFailure()).isNull();
        assertThatThrownBy(() -> watched.complete(REQUEST))
                .isInstanceOf(ModelException.class)
                .hasMessage("quota exhausted");
        assertThat(watched.takeFailure()).isEqualTo("quota exhausted");
        assertThat(watched.takeFailure()).isNull();
    }

    @Test
    void aWatchedClientCountsCallsAndTokensUntilTheyAreTaken() {
        WatchedModelClient watched = new WatchedModelClient(answering());

        watched.complete(REQUEST);
        watched.complete(REQUEST);

        assertThat(watched.takeUsage()).containsExactly(2L, 2L, 2L);
        assertThat(watched.takeUsage()).containsExactly(0L, 0L, 0L);
    }

    @Test
    void aWatchedClientPassesSuccessfulCallsThrough() {
        WatchedModelClient watched = new WatchedModelClient(answering());

        assertThat(watched.complete(REQUEST).text()).isEqualTo("ok");
        assertThat(watched.modelId()).isEqualTo("model-z");
        assertThat(watched.takeFailure()).isNull();
    }
}
