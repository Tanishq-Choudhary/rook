package dev.rook.control.policy;

import dev.rook.codeintel.impact.Confidence;
import java.util.List;

public record PolicyInput(
        List<String> changedPaths,
        int changedLines,
        double agentConfidence,
        boolean verified,
        long costMicros,
        Confidence weakestImpact) {

    public PolicyInput {
        changedPaths = List.copyOf(changedPaths);
        weakestImpact = weakestImpact == null ? Confidence.RESOLVED : weakestImpact;
    }
}
