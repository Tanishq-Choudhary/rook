package dev.rook.control.policy;

import java.util.List;

public record Policy(
        int maxFilesChanged,
        int maxChangedLines,
        List<String> allowedPaths,
        List<String> deniedPaths,
        boolean allowBuildFileChanges,
        boolean requireVerified,
        ApprovalMode approval,
        int approvalAboveFiles,
        int approvalAboveLines,
        double approvalBelowAgentConfidence,
        boolean approvalForTextualMatches,
        long maxJobCostMicros) {

    public Policy {
        if (maxFilesChanged < 1 || maxChangedLines < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
        if (approvalAboveFiles < 0 || approvalAboveLines < 0 || maxJobCostMicros < 0) {
            throw new IllegalArgumentException("thresholds must not be negative");
        }
        if (approvalBelowAgentConfidence < 0 || approvalBelowAgentConfidence > 1) {
            throw new IllegalArgumentException("confidence threshold must be between 0 and 1");
        }
        allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
        deniedPaths = deniedPaths == null ? List.of() : List.copyOf(deniedPaths);
        approval = approval == null ? ApprovalMode.THRESHOLD : approval;
    }

    public static Policy standard() {
        return new Policy(
                10,
                400,
                List.of(),
                List.of(".github/workflows/**", "**/Dockerfile", "**/*.sh"),
                false,
                true,
                ApprovalMode.THRESHOLD,
                3,
                80,
                0.7,
                true,
                2_000_000);
    }

    public Policy withApproval(ApprovalMode mode) {
        return new Policy(
                maxFilesChanged,
                maxChangedLines,
                allowedPaths,
                deniedPaths,
                allowBuildFileChanges,
                requireVerified,
                mode,
                approvalAboveFiles,
                approvalAboveLines,
                approvalBelowAgentConfidence,
                approvalForTextualMatches,
                maxJobCostMicros);
    }

    public Policy withLimits(int files, int lines) {
        return new Policy(
                files,
                lines,
                allowedPaths,
                deniedPaths,
                allowBuildFileChanges,
                requireVerified,
                approval,
                approvalAboveFiles,
                approvalAboveLines,
                approvalBelowAgentConfidence,
                approvalForTextualMatches,
                maxJobCostMicros);
    }

    public Policy withPaths(List<String> allowed, List<String> denied) {
        return new Policy(
                maxFilesChanged,
                maxChangedLines,
                allowed,
                denied,
                allowBuildFileChanges,
                requireVerified,
                approval,
                approvalAboveFiles,
                approvalAboveLines,
                approvalBelowAgentConfidence,
                approvalForTextualMatches,
                maxJobCostMicros);
    }

    public Policy allowingBuildFiles() {
        return new Policy(
                maxFilesChanged,
                maxChangedLines,
                allowedPaths,
                deniedPaths,
                true,
                requireVerified,
                approval,
                approvalAboveFiles,
                approvalAboveLines,
                approvalBelowAgentConfidence,
                approvalForTextualMatches,
                maxJobCostMicros);
    }

    public Policy withCostCap(long micros) {
        return new Policy(
                maxFilesChanged,
                maxChangedLines,
                allowedPaths,
                deniedPaths,
                allowBuildFileChanges,
                requireVerified,
                approval,
                approvalAboveFiles,
                approvalAboveLines,
                approvalBelowAgentConfidence,
                approvalForTextualMatches,
                micros);
    }
}
