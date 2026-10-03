import { Section } from "@/components/Section";
import { SubmitButton } from "@/components/SubmitButton";
import { api, query } from "@/lib/api";
import { formatMicros } from "@/lib/format";
import type { Installation, Usage } from "@/lib/types";
import { saveBudget } from "./actions";

export const dynamic = "force-dynamic";
export const metadata = { title: "Usage" };

function dollars(micros: number): string {
  return (micros / 1_000_000).toString();
}

export default async function UsagePage({
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
  const usage = await api<Usage>(`/api/v1/usage${query({ installation: selected.id })}`);
  const used = usage.monthlyCapMicros === 0 ? 1 : Math.min(1, (usage.spentMicros + usage.reservedMicros) / usage.monthlyCapMicros);
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Usage</h1>
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
          The budget was saved.
        </p>
      ) : null}
      <Section title={`This month for ${selected.accountLogin}`}>
        <div className="space-y-4">
          <div
            role="progressbar"
            aria-label="Share of the monthly budget used"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(used * 100)}
            className="h-2 w-full overflow-hidden rounded-full bg-raised"
          >
            <div className="h-full bg-accent" style={{ width: `${used * 100}%` }} />
          </div>
          <dl className="grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
            <div>
              <dt className="text-xs text-muted">Spent</dt>
              <dd className="font-medium">{formatMicros(usage.spentMicros)}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted">Held for running jobs</dt>
              <dd className="font-medium">{formatMicros(usage.reservedMicros)}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted">Left</dt>
              <dd className="font-medium">{formatMicros(usage.remainingMicros)}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted">Cap</dt>
              <dd className="font-medium">{formatMicros(usage.monthlyCapMicros)}</dd>
            </div>
          </dl>
          <p className="text-sm text-muted">
            Models {formatMicros(usage.modelMicros)}, sandboxes {formatMicros(usage.sandboxMicros)}.
          </p>
        </div>
      </Section>
      <Section title="Budget">
        <form action={saveBudget.bind(null, selected.id)} className="flex flex-wrap items-end gap-3">
          <div className="space-y-1">
            <label htmlFor="monthly" className="text-sm font-medium">
              Monthly cap in dollars
            </label>
            <input
              id="monthly"
              name="monthly"
              type="number"
              min={0}
              step="0.01"
              required
              defaultValue={dollars(usage.monthlyCapMicros)}
              className="rounded-md border border-line bg-surface px-3 py-2 text-sm"
            />
          </div>
          <div className="space-y-1">
            <label htmlFor="job" className="text-sm font-medium">
              Cap for one job in dollars
            </label>
            <input
              id="job"
              name="job"
              type="number"
              min={0}
              step="0.01"
              required
              defaultValue="2"
              className="rounded-md border border-line bg-surface px-3 py-2 text-sm"
            />
          </div>
          <SubmitButton tone="primary">Save budget</SubmitButton>
        </form>
      </Section>
    </div>
  );
}
