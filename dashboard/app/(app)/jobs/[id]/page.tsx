import { notFound } from "next/navigation";
import { ActionBar } from "@/components/ActionBar";
import { DiffViewer } from "@/components/DiffViewer";
import { ImpactList } from "@/components/ImpactList";
import { PolicyPanel } from "@/components/PolicyPanel";
import { Empty, Section } from "@/components/Section";
import { StateBadge } from "@/components/StateBadge";
import { Timeline } from "@/components/Timeline";
import { TracePanel } from "@/components/TracePanel";
import { VerificationPanel } from "@/components/VerificationPanel";
import { api } from "@/lib/api";
import { formatMicros, words } from "@/lib/format";
import { ApiError } from "@/lib/request";
import type { JobDetail } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata = { title: "Job" };

export default async function JobPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ error?: string }>;
}) {
  const { id } = await params;
  const { error } = await searchParams;
  let detail: JobDetail;
  try {
    detail = await api<JobDetail>(`/api/v1/jobs/${encodeURIComponent(id)}`);
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) {
      notFound();
    }
    throw failure;
  }
  const { job } = detail;
  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{job.repository}</h1>
          <p className="mt-1 text-sm text-muted">
            Attempt {job.attempt}, spend {formatMicros(detail.spendMicros)}
            {job.lastError ? `, ${job.lastError}` : ""}
          </p>
        </div>
        <div className="flex items-center gap-3">
          {detail.pullRequest ? (
            <a href={detail.pullRequest.url} className="text-sm text-accent" rel="noreferrer">
              Pull request #{detail.pullRequest.number} ({words(detail.pullRequest.state)})
            </a>
          ) : null}
          <StateBadge state={job.state} />
        </div>
      </header>
      {error ? (
        <p role="alert" className="rounded-md border border-bad/40 bg-bad/10 px-3 py-2 text-sm text-bad">
          {error}
        </p>
      ) : null}
      <ActionBar id={job.id} state={job.state} />
      <Section title="Impact">
        {detail.impact ? <ImpactList impact={detail.impact} /> : <Empty>The repository has not been analysed yet.</Empty>}
      </Section>
      <Section
        title="Proposed change"
        aside={detail.proposal ? <span className="text-xs text-muted">{detail.proposal.strategy}, {words(detail.proposal.status)}</span> : null}
      >
        {detail.proposal ? (
          <div className="space-y-4">
            {detail.proposal.unresolved.length > 0 ? (
              <div className="text-sm">
                <h3 className="mb-1 font-medium text-warn">Left for a person</h3>
                <ul className="font-mono text-xs">
                  {detail.proposal.unresolved.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </div>
            ) : null}
            <DiffViewer diff={detail.proposal.diff} />
            <div>
              <h3 className="mb-2 text-sm font-medium">How it was made</h3>
              <TracePanel steps={detail.proposal.trace} />
            </div>
          </div>
        ) : (
          <Empty>No patch has been proposed.</Empty>
        )}
      </Section>
      <Section title="Verification">
        {detail.verification ? <VerificationPanel verification={detail.verification} /> : <Empty>Not verified yet.</Empty>}
      </Section>
      {detail.policy ? (
        <Section title="Policy">
          <PolicyPanel decision={detail.policy} approval={detail.approval} />
        </Section>
      ) : null}
      <Section title="Timeline">
        <Timeline entries={detail.timeline} />
      </Section>
    </div>
  );
}
