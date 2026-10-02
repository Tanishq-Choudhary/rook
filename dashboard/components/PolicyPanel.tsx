import { words } from "@/lib/format";
import type { Approval, PolicyDecision } from "@/lib/types";

const OUTCOME: Record<string, string> = {
  ALLOW: "text-good",
  REQUIRE_APPROVAL: "text-warn",
  DENY: "text-bad",
};

export function PolicyPanel({ decision, approval }: { decision: PolicyDecision; approval: Approval | null }) {
  return (
    <div className="space-y-3 text-sm">
      <p>
        Policy outcome: <span className={`font-medium ${OUTCOME[decision.outcome] ?? ""}`}>{words(decision.outcome)}</span>
      </p>
      {decision.reasons.length > 0 ? (
        <ul className="space-y-1">
          {decision.reasons.map((reason) => (
            <li key={reason.code}>
              <span className="font-mono text-xs">{reason.code}</span> <span className="text-muted">{reason.message}</span>
            </li>
          ))}
        </ul>
      ) : null}
      {approval ? (
        <p className="text-muted">
          Review {words(approval.status)}
          {approval.decidedBy ? ` by ${approval.decidedBy}` : ""}
          {approval.note ? `: ${approval.note}` : ""}
        </p>
      ) : null}
    </div>
  );
}
