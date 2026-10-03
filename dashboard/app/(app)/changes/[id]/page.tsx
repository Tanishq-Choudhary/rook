import { notFound } from "next/navigation";
import { Empty, Section } from "@/components/Section";
import { SeverityBadge } from "@/components/SeverityBadge";
import { api } from "@/lib/api";
import { relativeTime, words } from "@/lib/format";
import { ApiError } from "@/lib/request";
import type { ChangeSetDetail } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata = { title: "Change" };

export default async function ChangePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  let detail: ChangeSetDetail;
  try {
    detail = await api<ChangeSetDetail>(`/api/v1/change-sets/${encodeURIComponent(id)}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }
  const { changeSet, events, notes } = detail;
  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">
            {changeSet.sourceId} {changeSet.fromVersion} to {changeSet.toVersion}
          </h1>
          <p className="mt-1 text-sm text-muted">
            Detected {relativeTime(changeSet.detectedAt)}.{" "}
            {changeSet.requiresMigration ? "Code that uses it needs a migration." : "No migration is needed."}
          </p>
        </div>
        <SeverityBadge severity={changeSet.highestSeverity} />
      </header>
      <Section title={`Events (${events.length})`}>
        {events.length === 0 ? (
          <Empty>No events were recorded.</Empty>
        ) : (
          <ul className="divide-y divide-line">
            {events.map((event) => (
              <li key={event.fingerprint} className="space-y-1 py-2 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <SeverityBadge severity={event.severity} />
                  <span className="font-medium">{event.summary}</span>
                </div>
                <p className="font-mono text-xs text-muted">
                  {words(event.kind)} on {event.subjectName}
                </p>
              </li>
            ))}
          </ul>
        )}
      </Section>
      {notes.length > 0 ? (
        <Section title="Migration notes">
          <ul className="space-y-3 text-sm">
            {notes.map((note) => (
              <li key={note.title}>
                <p className="font-medium">{note.title}</p>
                <p className="text-muted">{note.body}</p>
              </li>
            ))}
          </ul>
        </Section>
      ) : null}
    </div>
  );
}
