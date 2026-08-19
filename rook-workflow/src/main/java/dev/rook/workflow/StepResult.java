package dev.rook.workflow;

import dev.rook.core.job.JobState;
import java.time.Duration;
import java.util.Map;

public sealed interface StepResult {

    record Advance(JobState next, Map<String, Object> checkpoint) implements StepResult {

        public Advance {
            checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
        }
    }

    record Finish(JobState terminal, String reason, Map<String, Object> checkpoint)
            implements StepResult {

        public Finish {
            checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
        }
    }

    record Retry(Duration delay, String reason) implements StepResult {}

    static StepResult advance(JobState next) {
        return new Advance(next, Map.of());
    }

    static StepResult advance(JobState next, Map<String, Object> checkpoint) {
        return new Advance(next, checkpoint);
    }

    static StepResult finish(JobState terminal, String reason) {
        return new Finish(terminal, reason, Map.of());
    }

    static StepResult retryIn(Duration delay, String reason) {
        return new Retry(delay, reason);
    }
}
