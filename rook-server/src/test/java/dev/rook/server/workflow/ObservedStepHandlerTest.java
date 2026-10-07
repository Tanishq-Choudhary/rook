package dev.rook.server.workflow;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.rook.core.job.JobState;
import dev.rook.workflow.Job;
import dev.rook.workflow.JobContext;
import dev.rook.workflow.StepHandler;
import dev.rook.workflow.StepResult;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservedStepHandlerTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();
    private final UUID jobId = UUID.randomUUID();

    private JobContext context() {
        Job job =
                new Job(
                        jobId,
                        null,
                        null,
                        JobState.INDEXING,
                        1,
                        3,
                        0,
                        null,
                        null,
                        null,
                        1,
                        null,
                        null,
                        null,
                        null,
                        null);
        JobContext context = mock(JobContext.class);
        when(context.job()).thenReturn(job);
        return context;
    }

    private static StepHandler handler(StepResult result, Exception failure) {
        return new StepHandler() {
            @Override
            public JobState state() {
                return JobState.INDEXING;
            }

            @Override
            public StepResult execute(JobContext context) throws Exception {
                if (failure != null) {
                    throw failure;
                }
                return result;
            }
        };
    }

    @Test
    void passesTheStateAndResultThroughAndRecordsAnObservation() throws Exception {
        StepResult result = StepResult.advance(JobState.PROPOSING);
        ObservedStepHandler observed = new ObservedStepHandler(handler(result, null), registry);

        assertThat(observed.state()).isEqualTo(JobState.INDEXING);
        assertThat(observed.execute(context())).isSameAs(result);

        assertThat(registry)
                .hasSingleObservationThat()
                .hasNameEqualTo("rook.job.execute")
                .hasContextualNameEqualTo("job indexing")
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("state", "indexing")
                .hasHighCardinalityKeyValue("job", jobId.toString())
                .doesNotHaveError();
    }

    @Test
    void recordsTheErrorAndRethrowsIt() {
        IllegalStateException failure = new IllegalStateException("boom");
        ObservedStepHandler observed = new ObservedStepHandler(handler(null, failure), registry);

        assertThatThrownBy(() -> observed.execute(context())).isSameAs(failure);

        assertThat(registry)
                .hasSingleObservationThat()
                .hasBeenStopped()
                .assertThatError()
                .isSameAs(failure);
    }
}
