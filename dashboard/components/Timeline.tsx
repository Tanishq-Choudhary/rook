import { relativeTime, words } from "@/lib/format";
import type { TimelineEntry } from "@/lib/types";
import { StateBadge } from "./StateBadge";

function summary(data: Record<string, unknown>): string {
  const parts: string[] = [];
  for (const [name, value] of Object.entries(data)) {
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
      parts.push(`${name} ${value}`);
    }
  }
  return parts.join(", ");
}

export function Timeline({ entries }: { entries: TimelineEntry[] }) {
  if (entries.length === 0) {
    return <p className="text-sm text-muted">Nothing has happened yet.</p>;
  }
  return (
    <ol aria-label="Job timeline" className="space-y-3">
      {entries.map((entry) => (
        <li key={entry.id} className="flex gap-3 text-sm">
          <time dateTime={entry.at} title={entry.at} className="w-28 shrink-0 text-xs text-muted">
            {relativeTime(entry.at)}
          </time>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{words(entry.kind)}</span>
              {entry.state ? <StateBadge state={entry.state} /> : null}
            </div>
            {summary(entry.data) ? <p className="truncate text-xs text-muted">{summary(entry.data)}</p> : null}
          </div>
        </li>
      ))}
    </ol>
  );
}
