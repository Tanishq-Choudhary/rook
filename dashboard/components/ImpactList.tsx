import { words } from "@/lib/format";
import type { Impact } from "@/lib/types";

export function ImpactList({ impact }: { impact: Impact }) {
  const entries = impact.report.impacts ?? [];
  return (
    <div className="space-y-4 text-sm">
      <p className="text-muted">{impact.summary}</p>
      {entries.map((entry) => (
        <div key={entry.fingerprint} className="rounded-md border border-line">
          <div className="border-b border-line bg-raised px-3 py-2">
            <span className="font-medium">{entry.summary}</span>{" "}
            <span className="text-xs text-muted">{words(entry.kind)}</span>
          </div>
          <ul className="divide-y divide-line">
            {entry.locations.map((location) => (
              <li
                key={`${location.file}:${location.line}:${location.column}`}
                className="flex flex-wrap items-baseline gap-x-3 px-3 py-1.5"
              >
                <span className="font-mono text-xs">
                  {location.file}:{location.line}
                </span>
                <span className="text-xs text-muted">
                  {words(location.referenceKind)}, {location.confidence.toLowerCase()}
                  {location.test ? ", test code" : ""}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}
