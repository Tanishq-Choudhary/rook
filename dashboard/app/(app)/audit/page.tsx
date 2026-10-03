import { Empty, Section } from "@/components/Section";
import { api, query } from "@/lib/api";
import { relativeTime } from "@/lib/format";
import { ApiError } from "@/lib/request";

export const dynamic = "force-dynamic";
export const metadata = { title: "Audit" };

interface Entry {
  seq: number;
  at: string;
  actor: string;
  action: string;
  subject: string;
  hash: string;
}

interface Verification {
  intact: boolean;
  checked: number;
  firstBadSeq: number | null;
  problem: string;
  head: string;
}

export default async function AuditPage({ searchParams }: { searchParams: Promise<{ subject?: string }> }) {
  const { subject } = await searchParams;
  let entries: Entry[];
  let verification: Verification;
  try {
    [entries, verification] = await Promise.all([
      api<Entry[]>(`/api/v1/audit${query({ subject, limit: 100 })}`),
      api<Verification>("/api/v1/audit/verify"),
    ]);
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return <Empty>The audit trail needs an admin key.</Empty>;
    }
    throw error;
  }
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Audit</h1>
        <form method="get" className="flex items-end gap-2">
          <div className="space-y-1">
            <label htmlFor="subject" className="block text-xs text-muted">
              Subject
            </label>
            <input
              id="subject"
              name="subject"
              defaultValue={subject ?? ""}
              placeholder="job:..."
              className="rounded-md border border-line bg-surface px-2 py-1.5 text-sm"
            />
          </div>
          <button type="submit" className="rounded-md border border-line px-3 py-1.5 text-sm">
            Filter
          </button>
        </form>
      </div>
      <div
        role="status"
        className={`rounded-md border px-3 py-2 text-sm ${
          verification.intact ? "border-good/40 bg-good/10 text-good" : "border-bad/40 bg-bad/10 text-bad"
        }`}
      >
        {verification.intact
          ? `The hash chain is intact across ${verification.checked} entries. Head ${verification.head.slice(0, 12)}.`
          : `The chain is broken at entry ${verification.firstBadSeq}: ${verification.problem}`}
      </div>
      <Section title="Entries">
        {entries.length === 0 ? (
          <Empty>Nothing has been recorded.</Empty>
        ) : (
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Audit entries</caption>
            <thead className="text-xs text-muted">
              <tr>
                <th scope="col" className="pb-2 font-medium">
                  When
                </th>
                <th scope="col" className="pb-2 font-medium">
                  Who
                </th>
                <th scope="col" className="pb-2 font-medium">
                  What
                </th>
                <th scope="col" className="pb-2 font-medium">
                  About
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {entries.map((entry) => (
                <tr key={entry.seq}>
                  <td className="py-2 text-muted">{relativeTime(entry.at)}</td>
                  <td className="py-2">{entry.actor}</td>
                  <td className="py-2 font-mono text-xs">{entry.action}</td>
                  <td className="py-2 font-mono text-xs text-muted">{entry.subject}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
    </div>
  );
}
