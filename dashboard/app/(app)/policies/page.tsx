import { PolicyForm } from "@/components/PolicyForm";
import { Section } from "@/components/Section";
import { api, query } from "@/lib/api";
import type { PolicyBody } from "@/lib/policy";
import type { Installation } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata = { title: "Policies" };

interface Effective {
  version: number;
  policy: PolicyBody;
}

export default async function PoliciesPage({
  searchParams,
}: {
  searchParams: Promise<{ installation?: string; error?: string; saved?: string }>;
}) {
  const params = await searchParams;
  const installations = await api<Installation[]>("/api/v1/installations");
  const selected = installations.find((item) => item.id === params.installation) ?? installations[0];
  if (!selected) {
    return <p className="text-sm text-muted">No installations exist yet.</p>;
  }
  const effective = await api<Effective>(`/api/v1/policies/effective${query({ installation: selected.id })}`);
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Policies</h1>
        {installations.length > 1 ? (
          <form method="get" className="flex items-end gap-2">
            <div className="space-y-1">
              <label htmlFor="installation" className="block text-xs text-muted">
                Installation
              </label>
              <select
                id="installation"
                name="installation"
                defaultValue={selected.id}
                className="rounded-md border border-line bg-surface px-2 py-1.5 text-sm"
              >
                {installations.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.accountLogin}
                  </option>
                ))}
              </select>
            </div>
            <button type="submit" className="rounded-md border border-line px-3 py-1.5 text-sm">
              Show
            </button>
          </form>
        ) : null}
      </div>
      {params.error ? (
        <p role="alert" className="rounded-md border border-bad/40 bg-bad/10 px-3 py-2 text-sm text-bad">
          {params.error}
        </p>
      ) : null}
      {params.saved ? (
        <p role="status" className="rounded-md border border-good/40 bg-good/10 px-3 py-2 text-sm text-good">
          The policy was saved.
        </p>
      ) : null}
      <Section
        title={`Policy for ${selected.accountLogin}`}
        aside={<span className="text-xs text-muted">{effective.version === 0 ? "standard defaults" : `version ${effective.version}`}</span>}
      >
        <PolicyForm installation={selected.id} policy={effective.policy} editable />
      </Section>
    </div>
  );
}
