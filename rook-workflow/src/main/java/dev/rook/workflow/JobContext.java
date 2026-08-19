package dev.rook.workflow;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;

public final class JobContext {

    private final Job job;
    private final String workerId;
    private final JobRepository jobs;
    private final Duration lease;
    private final AtomicBoolean leaseLost = new AtomicBoolean();

    JobContext(Job job, String workerId, JobRepository jobs, Duration lease) {
        this.job = job;
        this.workerId = workerId;
        this.jobs = jobs;
        this.lease = lease;
    }

    public Job job() {
        return job;
    }

    public JsonNode checkpoint() {
        return job.checkpoint();
    }

    public String workerId() {
        return workerId;
    }

    public long fence() {
        return job.fence();
    }

    public void heartbeat() {
        if (!jobs.heartbeat(job.id(), job.fence(), lease)) {
            leaseLost.set(true);
            throw new LeaseLostException(job.id());
        }
    }

    public boolean leaseLost() {
        return leaseLost.get();
    }

    void markLeaseLost() {
        leaseLost.set(true);
    }
}
