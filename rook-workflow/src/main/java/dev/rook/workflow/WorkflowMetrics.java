package dev.rook.workflow;

import dev.rook.core.job.JobState;
import java.time.Duration;

public interface WorkflowMetrics {

    WorkflowMetrics NOOP =
            new WorkflowMetrics() {
                @Override
                public void step(JobState state, String outcome, Duration took) {}

                @Override
                public void leaseLost(JobState state) {}

                @Override
                public void claimed(boolean reclaimed) {}
            };

    void step(JobState state, String outcome, Duration took);

    void leaseLost(JobState state);

    void claimed(boolean reclaimed);
}
