package dev.rook.pipeline.step;

import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.control.policy.Decision;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.Severity;
import dev.rook.github.publish.PrReport;
import dev.rook.pipeline.store.ArtifactStore;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class ReportAssembler {

    private ReportAssembler() {}

    public static PrReport assemble(
            String repository,
            ChangeSet changeSet,
            ArtifactStore.StoredImpact impact,
            ArtifactStore.StoredProposal proposal,
            ArtifactStore.StoredVerification verification,
            Decision decision,
            String jobUrl) {
        List<String> changes = new ArrayList<>();
        for (ChangeEvent event : changeSet.events()) {
            if (event.severity().atLeast(Severity.DEPRECATION) && !event.kind().isAdvisory()) {
                changes.add(event.summary());
            }
        }
        List<PrReport.CallSite> sites = new ArrayList<>();
        for (Impact entry : impact.report().impacts()) {
            for (ImpactLocation location : entry.locations()) {
                sites.add(
                        new PrReport.CallSite(
                                location.file(),
                                location.line(),
                                location.referenceKind().toLowerCase().replace('_', ' ')
                                        + ", "
                                        + location.confidence().name().toLowerCase()));
            }
        }
        return new PrReport(
                repository,
                changeSet.sourceId(),
                changeSet.fromVersion(),
                changeSet.toVersion(),
                changes,
                sites,
                diffStats(proposal),
                verification(verification),
                risks(proposal, verification, decision),
                proposal.strategy(),
                jobUrl);
    }

    static PrReport.Diff diffStats(ArtifactStore.StoredProposal proposal) {
        int added = 0;
        int removed = 0;
        for (String line : proposal.diff().split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                added++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                removed++;
            }
        }
        return new PrReport.Diff(
                proposal.files().size() + proposal.deletions().size(), added, removed);
    }

    static PrReport.Verification verification(ArtifactStore.StoredVerification stored) {
        List<String> flaky = new ArrayList<>();
        List<String> gates = new ArrayList<>();
        if (stored != null) {
            strings(stored.report().path("flaky")).forEach(flaky::add);
            for (JsonNode gate : stored.report().path("gates")) {
                if (gate.path("passed").asBoolean(false)) {
                    gates.add(gate.path("name").asString(""));
                }
            }
        }
        return new PrReport.Verification(
                orZero(stored == null ? null : stored.baselineRun()),
                orZero(stored == null ? null : stored.baselineFailed()),
                orZero(stored == null ? null : stored.patchedRun()),
                orZero(stored == null ? null : stored.patchedFailed()),
                flaky,
                gates);
    }

    static List<String> risks(
            ArtifactStore.StoredProposal proposal,
            ArtifactStore.StoredVerification verification,
            Decision decision) {
        List<String> risks = new ArrayList<>();
        for (String item : proposal.unresolved()) {
            risks.add("Not changed automatically: " + item);
        }
        if (verification != null
                && verification.baselineFailed() != null
                && verification.baselineFailed() > 0) {
            risks.add(
                    verification.baselineFailed()
                            + " tests were already failing before this change and are left alone.");
        }
        if (decision != null) {
            decision.reasons()
                    .forEach(reason -> risks.add("Review requested: " + reason.message()));
        }
        return risks;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString("")));
        return values;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
