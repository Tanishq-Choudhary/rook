package dev.rook.pipeline.step;

import dev.rook.control.audit.AuditLog;
import dev.rook.control.store.ApprovalStore;
import dev.rook.workflow.JobRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class ApprovalService {

    public enum Result {
        APPLIED,
        NOT_PENDING,
        JOB_NOT_WAITING
    }

    private final ApprovalStore approvals;
    private final JobRepository jobs;
    private final AuditLog audit;

    public ApprovalService(ApprovalStore approvals, JobRepository jobs, AuditLog audit) {
        this.approvals = approvals;
        this.jobs = jobs;
        this.audit = audit;
    }

    public Result approve(UUID jobId, String reviewer, String note) {
        return decide(jobId, true, reviewer, note);
    }

    public Result reject(UUID jobId, String reviewer, String note) {
        return decide(jobId, false, reviewer, note);
    }

    private Result decide(UUID jobId, boolean approved, String reviewer, String note) {
        if (!approvals.decide(jobId, approved, reviewer, note)) {
            return Result.NOT_PENDING;
        }
        boolean moved = jobs.recordApproval(jobId, approved, reviewer);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reviewer", reviewer);
        if (note != null && !note.isBlank()) {
            facts.put("note", note);
        }
        audit.append(reviewer, approved ? "APPROVED" : "REJECTED", "job:" + jobId, facts);
        return moved ? Result.APPLIED : Result.JOB_NOT_WAITING;
    }
}
