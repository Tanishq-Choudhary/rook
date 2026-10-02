import Link from "next/link";
import { Empty, Section } from "@/components/Section";
import { StateBadge } from "@/components/StateBadge";
import { api, query } from "@/lib/api";
import { formatMicros, relativeTime, words } from "@/lib/format";
import { OPEN_STATES } from "@/lib/states";
import type { Installation, JobSummary, Page, Usage } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata = { title: "Overview" };

const HEADLINE = [...OPEN_STATES.filter((s) => s === "AWAITING_APPROVAL" || s === "PENDING"), "FAILED", "COMPLETED"];

async function total(state: string): Promise<number> {
  const page = await api<Page<JobSummary>>(`/api/v1/jobs${query({ state, size: 1 })}`);
  return page.total;
}

async function usageIfSingle(): Promise<Usage | null> {
  const installations = await api<Installation[]>("/api/v1/installations");
  if (installations.length !== 1) {
    return null;
  }
  return api<Usage>(`/api/v1/usage${query({ installation: installations[0].id })}`);
}

export default async function OverviewPage() {
  const [recent, totals, usage] = await Promise.all([
    api<Page<JobSummary>>(`/api/v1/jobs${query({ size: 8 })}`),
    Promise.all(HEADLINE.map(async (state) => [state, await total(state)] as const)),
    usageIfSingle(),
  ]);
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold tracking-tight">Overview</h1>
      <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
        {totals.map(([state, count]) => (
          <div key={state} className="rounded-lg border border-line bg-raised p-4">
            <dt className="text-xs text-muted">{words(state)}</dt>
            <dd className="mt-1 text-2xl font-semibold" data-testid={`total-${state}`}>
              {count}
            </dd>
          </div>
        ))}
      </dl>
      {usage ? (
        <Section title="This month">
          <p className="text-sm">
            {formatMicros(usage.spentMicros)} spent of {formatMicros(usage.monthlyCapMicros)}, {formatMicros(usage.remainingMicros)} left.
          </p>
        </Section>
      ) : null}
      <Section title="Recent jobs" aside={<Link href="/jobs" className="text-sm text-accent">All jobs</Link>}>
        {recent.items.length === 0 ? (
          <Empty>No jobs yet. They appear when a watched source changes.</Empty>
        ) : (
          <ul className="divide-y divide-line">
            {recent.items.map((job) => (
              <li key={job.id} className="flex items-center justify-between gap-3 py-2">
                <Link href={`/jobs/${job.id}`} className="truncate text-sm font-medium hover:underline">
                  {job.repository}
                </Link>
                <span className="flex shrink-0 items-center gap-3">
                  <span className="text-xs text-muted">{relativeTime(job.updatedAt)}</span>
                  <StateBadge state={job.state} />
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}
