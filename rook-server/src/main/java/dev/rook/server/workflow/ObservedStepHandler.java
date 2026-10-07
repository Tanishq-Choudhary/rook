package dev.rook.server.workflow;

import dev.rook.core.job.JobState;
import dev.rook.workflow.JobContext;
import dev.rook.workflow.StepHandler;
import dev.rook.workflow.StepResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;

public final class ObservedStepHandler implements StepHandler {

    private final StepHandler delegate;
    private final ObservationRegistry registry;

    public ObservedStepHandler(StepHandler delegate, ObservationRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public JobState state() {
        return delegate.state();
    }

    @Override
    public StepResult execute(JobContext context) throws Exception {
        Observation observation =
                Observation.createNotStarted("rook.job.execute", registry)
                        .lowCardinalityKeyValue("state", state().name().toLowerCase(Locale.ROOT))
                        .highCardinalityKeyValue("job", context.job().id().toString())
                        .contextualName("job " + state().name().toLowerCase(Locale.ROOT));
        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            return delegate.execute(context);
        } catch (Exception | Error failure) {
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }
}
