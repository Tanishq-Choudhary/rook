import { formatDuration } from "@/lib/format";
import type { Verification } from "@/lib/types";

function counts(run: number | null, failed: number | null): string {
  return run === null ? "not run" : `${run} ran, ${failed ?? 0} failed`;
}

export function VerificationPanel({ verification }: { verification: Verification }) {
  const gates = verification.report.gates ?? [];
  const flaky = verification.report.flaky ?? [];
  const regressions = verification.report.regressions ?? [];
  return (
    <div className="space-y-4 text-sm">
      <p>
        <span className={verification.passed ? "font-medium text-good" : "font-medium text-bad"}>
          {verification.passed ? "Passed" : "Did not pass"}
        </span>{" "}
        in {formatDuration(verification.elapsedMillis)}. {verification.summary}
      </p>
      <dl className="grid grid-cols-2 gap-3">
        <div className="rounded-md border border-line bg-raised p-3">
          <dt className="text-xs text-muted">Before the change</dt>
          <dd>{counts(verification.baselineRun, verification.baselineFailed)}</dd>
        </div>
        <div className="rounded-md border border-line bg-raised p-3">
          <dt className="text-xs text-muted">After the change</dt>
          <dd>{counts(verification.patchedRun, verification.patchedFailed)}</dd>
        </div>
      </dl>
      {gates.length > 0 ? (
        <ul aria-label="Verification gates" className="space-y-1">
          {gates.map((gate) => (
            <li key={gate.name} className="flex items-baseline gap-2">
              <span className={gate.passed ? "text-good" : "text-bad"} aria-label={gate.passed ? "passed" : "failed"}>
                {gate.passed ? "pass" : "fail"}
              </span>
              <span className="font-mono text-xs">{gate.name}</span>
              {gate.detail ? <span className="text-xs text-muted">{gate.detail}</span> : null}
            </li>
          ))}
        </ul>
      ) : null}
      {regressions.length > 0 ? (
        <div>
          <h3 className="mb-1 text-xs font-semibold text-bad">New failures</h3>
          <ul className="font-mono text-xs">
            {regressions.map((name) => (
              <li key={name}>{name}</li>
            ))}
          </ul>
        </div>
      ) : null}
      {flaky.length > 0 ? (
        <div>
          <h3 className="mb-1 text-xs font-semibold text-warn">Inconsistent when re-run</h3>
          <ul className="font-mono text-xs">
            {flaky.map((name) => (
              <li key={name}>{name}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
