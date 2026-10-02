import { formatDuration, words } from "@/lib/format";
import type { TraceStep } from "@/lib/types";

export function TracePanel({ steps }: { steps: TraceStep[] }) {
  if (steps.length === 0) {
    return <p className="text-sm text-muted">The patch came from deterministic edits, so no model steps were taken.</p>;
  }
  return (
    <ol aria-label="Agent trace" className="space-y-2 text-sm">
      {steps.map((step) => (
        <li key={step.sequence} className="flex gap-3">
          <span className="w-6 shrink-0 text-right text-xs text-muted">{step.sequence}</span>
          <div className="min-w-0">
            <p>
              <span className="font-medium">{words(step.kind)}</span>{" "}
              <span className="font-mono text-xs">{step.name}</span>{" "}
              <span className="text-xs text-muted">{formatDuration(step.millis)}</span>
              {step.error ? <span className="ml-2 text-xs text-bad">failed</span> : null}
            </p>
            {step.detail ? <p className="truncate font-mono text-xs text-muted">{step.detail}</p> : null}
          </div>
        </li>
      ))}
    </ol>
  );
}
