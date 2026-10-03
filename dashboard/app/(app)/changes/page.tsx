import Link from "next/link";
import { Empty, Section } from "@/components/Section";
import { Pager } from "@/components/Pager";
import { SeverityBadge } from "@/components/SeverityBadge";
import { api, query } from "@/lib/api";
import { relativeTime } from "@/lib/format";
import type { ChangeSetSummary, Page, Source } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata = { title: "Changes" };

export default async function ChangesPage({
  searchParams,
}: {
  searchParams: Promise<{ source?: string; page?: string }>;
}) {
  const { source, page } = await searchParams;
  const sources = await api<Source[]>("/api/v1/sources");
  const selected = source && sources.some((s) => s.id === source) ? source : undefined;
  const number = Math.max(0, Number(page ?? 0) || 0);
  const changes = await api<Page<ChangeSetSummary>>(
    `/api/v1/change-sets${query({ source: selected, page: number, size: 20 })}`,
  );
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Changes</h1>
        <form method="get" className="flex items-end gap-2">
          <div className="space-y-1">
            <label htmlFor="source" className="block text-xs text-muted">
              Source
            </label>
            <select
              id="source"
              name="source"
              defaultValue={selected ?? ""}
              className="rounded-md border border-line bg-surface px-2 py-1.5 text-sm"
            >
              <option value="">All</option>
              {sources.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.displayName}
                </option>
              ))}
            </select>
          </div>
          <button type="submit" className="rounded-md border border-line px-3 py-1.5 text-sm">
            Filter
          </button>
        </form>
      </div>
      <Section title="Detected upstream changes">
        {changes.items.length === 0 ? (
          <Empty>Nothing has changed upstream since Rook started watching.</Empty>
        ) : (
          <ul className="divide-y divide-line">
            {changes.items.map((change) => (
              <li key={change.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <Link href={`/changes/${change.id}`} className="text-sm font-medium hover:underline">
                  {change.sourceId} {change.fromVersion} to {change.toVersion}
                </Link>
                <span className="flex items-center gap-3 text-xs text-muted">
                  <span>{change.events} events</span>
                  <span>{relativeTime(change.detectedAt)}</span>
                  <SeverityBadge severity={change.highestSeverity} />
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Pager page={changes} path="/changes" params={{ source: selected }} />
    </div>
  );
}
