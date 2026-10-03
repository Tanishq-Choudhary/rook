import Link from "next/link";
import { Empty, Section } from "@/components/Section";
import { Pager } from "@/components/Pager";
import { StateBadge } from "@/components/StateBadge";
import { api, query } from "@/lib/api";
import { relativeTime } from "@/lib/format";
import { ALL_STATES } from "@/lib/states";
import type { JobSummary, Page } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata = { title: "Jobs" };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default async function JobsPage({
  searchParams,
}: {
  searchParams: Promise<{ state?: string; page?: string; repository?: string }>;
}) {
  const { state, page, repository } = await searchParams;
  const scoped = repository && UUID.test(repository) ? repository : undefined;
  const selected = state && ALL_STATES.includes(state) ? state : undefined;
  const number = Math.max(0, Number(page ?? 0) || 0);
  const jobs = await api<Page<JobSummary>>(`/api/v1/jobs${query({ state: selected, repository: scoped, page: number, size: 20 })}`);
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Jobs</h1>
        <form method="get" className="flex items-end gap-2">
          {scoped ? <input type="hidden" name="repository" value={scoped} /> : null}
          <div className="space-y-1">
            <label htmlFor="state" className="block text-xs text-muted">
              State
            </label>
            <select
              id="state"
              name="state"
              defaultValue={selected ?? ""}
              className="rounded-md border border-line bg-surface px-2 py-1.5 text-sm"
            >
              <option value="">All</option>
              {ALL_STATES.map((name) => (
                <option key={name} value={name}>
                  {name.toLowerCase().replace(/_/g, " ")}
                </option>
              ))}
            </select>
          </div>
          <button type="submit" className="rounded-md border border-line px-3 py-1.5 text-sm">
            Filter
          </button>
        </form>
      </div>
      <Section title="Migration jobs">
        {jobs.items.length === 0 ? (
          <Empty>No jobs match.</Empty>
        ) : (
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Migration jobs</caption>
            <thead className="text-xs text-muted">
              <tr>
                <th scope="col" className="pb-2 font-medium">
                  Repository
                </th>
                <th scope="col" className="pb-2 font-medium">
                  State
                </th>
                <th scope="col" className="pb-2 font-medium">
                  Attempt
                </th>
                <th scope="col" className="pb-2 font-medium">
                  Updated
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {jobs.items.map((job) => (
                <tr key={job.id}>
                  <td className="py-2">
                    <Link href={`/jobs/${job.id}`} className="font-medium hover:underline">
                      {job.repository}
                    </Link>
                  </td>
                  <td className="py-2">
                    <StateBadge state={job.state} />
                  </td>
                  <td className="py-2">{job.attempt}</td>
                  <td className="py-2 text-muted">{relativeTime(job.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
      <Pager page={jobs} path="/jobs" params={{ state: selected, repository: scoped }} />
    </div>
  );
}
