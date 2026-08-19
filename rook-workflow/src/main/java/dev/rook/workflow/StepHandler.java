package dev.rook.workflow;

import dev.rook.core.job.JobState;

public interface StepHandler {

    JobState state();

    StepResult execute(JobContext context) throws Exception;
}
