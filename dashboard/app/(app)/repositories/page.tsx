import Link from "next/link";
import { Empty, Section } from "@/components/Section";
import { Pager } from "@/components/Pager";
import { api, query } from "@/lib/api";
import { relativeTime, shortSha } from "@/lib/format";
import type { Page, Repository, Source } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata = { title: "Repositories" };

export default async function RepositoriesPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  const { page } = await searchParams;
  const number = Math.max(0, Number(page ?? 0) || 0);
  const [repositories, sources] = await Promise.all([
    api<Page<Repository>>(`/api/v1/repositories${query({ page: number, size: 20 })}`),
    api<Source[]>("/api/v1/sources"),
  ]);
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold tracking-tight">Repositories</h1>
      <Section title="Connected repositories">
        {repositories.items.length === 0 ? (
          <Empty>No repositories are connected. Install the GitHub App to add some.</Empty>
        ) : (
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Connected repositories</caption>
            <thead className="text-xs text-muted">
              <tr>
                <th scope="col" className="pb-2 font-medium">
                  Repository
                </th>
                <th scope="col" className="pb-2 font-medium">
                  Watching
                </th>
                <th scope="col" className="pb-2 font-medium">
                  Last indexed
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {repositories.items.map((repository) => (
                <tr key={repository.id}>
                  <td className="py-2">
                    <Link href={`/jobs?repository=${repository.id}`} className="font-medium hover:underline">
                      {repository.fullName}
                    </Link>
                    {repository.enabled ? null : <span className="ml-2 text-xs text-muted">disabled</span>}
                  </td>
                  <td className="py-2 text-muted">
                    {repository.watches.length === 0 ? "nothing yet" : repository.watches.join(", ")}
                  </td>
                  <td className="py-2 text-muted">{shortSha(repository.lastIndexedSha)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
      <Pager page={repositories} path="/repositories" params={{}} />
      <Section title="Watched sources">
        <ul className="divide-y divide-line">
          {sources.map((source) => (
            <li key={source.id} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
              <span>
                <span className="font-medium">{source.displayName}</span>{" "}
                <span className="text-xs text-muted">{source.kind.toLowerCase().replace(/_/g, " ")}</span>
              </span>
              <span className="text-xs text-muted">
                {source.lastError
                  ? `last check failed: ${source.lastError}`
                  : `version ${source.lastVersion ?? "unknown"}, checked ${relativeTime(source.lastCheckedAt)}`}
              </span>
            </li>
          ))}
        </ul>
      </Section>
    </div>
  );
}
