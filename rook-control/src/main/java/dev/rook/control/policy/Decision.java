package dev.rook.control.policy;

import java.util.List;

public record Decision(Outcome outcome, List<Reason> reasons) {

    public enum Outcome {
        ALLOW,
        REQUIRE_APPROVAL,
        DENY
    }

    public Decision {
        reasons = List.copyOf(reasons);
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOW;
    }

    public boolean denied() {
        return outcome == Outcome.DENY;
    }

    public boolean needsApproval() {
        return outcome == Outcome.REQUIRE_APPROVAL;
    }

    public boolean has(String code) {
        return reasons.stream().anyMatch(reason -> reason.code().equals(code));
    }
}
