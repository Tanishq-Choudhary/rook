package dev.rook.control.policy;

import dev.rook.agent.workspace.WorkspacePolicy;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.core.security.SensitivePaths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PolicyEngine {

    private PolicyEngine() {}

    public static Decision evaluate(Policy policy, PolicyInput input) {
        List<Reason> denials = new ArrayList<>();
        List<Reason> approvals = new ArrayList<>();
        checkPatch(policy, input, denials);
        checkPaths(policy, input, denials, approvals);
        if (policy.approval() == ApprovalMode.ALWAYS) {
            approvals.add(
                    new Reason(
                            "APPROVAL_REQUIRED", "this policy asks for a review of every change"));
        } else if (policy.approval() == ApprovalMode.THRESHOLD) {
            checkThresholds(policy, input, approvals);
        }
        if (!denials.isEmpty()) {
            return new Decision(Decision.Outcome.DENY, denials);
        }
        if (!approvals.isEmpty()) {
            return new Decision(Decision.Outcome.REQUIRE_APPROVAL, approvals);
        }
        return new Decision(Decision.Outcome.ALLOW, List.of());
    }

    private static void checkPatch(Policy policy, PolicyInput input, List<Reason> denials) {
        if (input.changedPaths().isEmpty()) {
            denials.add(new Reason("EMPTY_PATCH", "the patch changes nothing"));
        }
        if (policy.requireVerified() && !input.verified()) {
            denials.add(new Reason("UNVERIFIED", "the patch did not pass verification"));
        }
        if (input.changedPaths().size() > policy.maxFilesChanged()) {
            denials.add(
                    new Reason(
                            "TOO_MANY_FILES",
                            input.changedPaths().size()
                                    + " files changed, the limit is "
                                    + policy.maxFilesChanged()));
        }
        if (input.changedLines() > policy.maxChangedLines()) {
            denials.add(
                    new Reason(
                            "TOO_MANY_LINES",
                            input.changedLines()
                                    + " lines changed, the limit is "
                                    + policy.maxChangedLines()));
        }
        if (input.costMicros() > policy.maxJobCostMicros()) {
            denials.add(
                    new Reason(
                            "COST_OVER_CAP",
                            "the job cost "
                                    + input.costMicros()
                                    + " micros, the cap is "
                                    + policy.maxJobCostMicros()));
        }
    }

    private static void checkPaths(
            Policy policy, PolicyInput input, List<Reason> denials, List<Reason> approvals) {
        for (String path : input.changedPaths()) {
            String normalized = Glob.normalize(path);
            if (SensitivePaths.isSensitive(normalized)) {
                denials.add(new Reason("SENSITIVE_PATH", normalized + " may hold secrets"));
                continue;
            }
            if (WorkspacePolicy.isProtectedFromEdits(normalized)) {
                denials.add(
                        new Reason(
                                "PROTECTED_PATH",
                                normalized + " cannot be changed by a migration"));
                continue;
            }
            if (policy.deniedPaths().stream().anyMatch(glob -> Glob.matches(glob, normalized))) {
                denials.add(new Reason("DENIED_PATH", normalized + " matches a denied path"));
                continue;
            }
            if (!policy.allowedPaths().isEmpty()
                    && policy.allowedPaths().stream()
                            .noneMatch(glob -> Glob.matches(glob, normalized))) {
                denials.add(
                        new Reason(
                                "PATH_NOT_ALLOWED", normalized + " is outside the allowed paths"));
                continue;
            }
            if (WorkspacePolicy.isBuildFile(normalized)) {
                if (policy.allowBuildFileChanges()) {
                    approvals.add(
                            new Reason(
                                    "BUILD_FILE_CHANGE",
                                    normalized + " is a build file and needs a review"));
                } else {
                    denials.add(new Reason("BUILD_FILE_DENIED", normalized + " is a build file"));
                }
            }
        }
    }

    private static void checkThresholds(Policy policy, PolicyInput input, List<Reason> approvals) {
        if (input.changedPaths().size() > policy.approvalAboveFiles()) {
            approvals.add(
                    new Reason(
                            "LARGE_PATCH_FILES",
                            input.changedPaths().size()
                                    + " files changed, reviews start above "
                                    + policy.approvalAboveFiles()));
        }
        if (input.changedLines() > policy.approvalAboveLines()) {
            approvals.add(
                    new Reason(
                            "LARGE_PATCH_LINES",
                            input.changedLines()
                                    + " lines changed, reviews start above "
                                    + policy.approvalAboveLines()));
        }
        if (input.agentConfidence() < policy.approvalBelowAgentConfidence()) {
            approvals.add(
                    new Reason(
                            "LOW_CONFIDENCE",
                            String.format(
                                    Locale.ROOT,
                                    "agent confidence %.2f is below %.2f",
                                    input.agentConfidence(),
                                    policy.approvalBelowAgentConfidence())));
        }
        if (policy.approvalForTextualMatches() && input.weakestImpact() == Confidence.TEXTUAL) {
            approvals.add(
                    new Reason("TEXTUAL_MATCH", "part of the change rests on a plain text match"));
        }
    }
}
