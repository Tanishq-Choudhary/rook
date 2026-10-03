import { savePolicy } from "@/app/(app)/policies/actions";
import type { PolicyBody } from "@/lib/policy";
import { SubmitButton } from "./SubmitButton";

const FIELD = "w-full rounded-md border border-line bg-surface px-3 py-2 text-sm";

function NumberField({ name, label, value, step }: { name: string; label: string; value: number; step?: string }) {
  return (
    <div className="space-y-1">
      <label htmlFor={name} className="text-sm font-medium">
        {label}
      </label>
      <input id={name} name={name} type="number" min={0} step={step ?? "1"} defaultValue={value} required className={FIELD} />
    </div>
  );
}

function Check({ name, label, checked }: { name: string; label: string; checked: boolean }) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <input type="checkbox" name={name} defaultChecked={checked} />
      {label}
    </label>
  );
}

export function PolicyForm({ installation, policy, editable }: { installation: string; policy: PolicyBody; editable: boolean }) {
  return (
    <form action={savePolicy.bind(null, installation)} className="space-y-5">
      <fieldset disabled={!editable} className="space-y-5">
        <div className="grid gap-4 sm:grid-cols-2">
          <NumberField name="maxFilesChanged" label="Most files a patch may change" value={policy.maxFilesChanged} />
          <NumberField name="maxChangedLines" label="Most lines a patch may change" value={policy.maxChangedLines} />
          <NumberField name="approvalAboveFiles" label="Ask for a review above this many files" value={policy.approvalAboveFiles} />
          <NumberField name="approvalAboveLines" label="Ask for a review above this many lines" value={policy.approvalAboveLines} />
          <NumberField
            name="approvalBelowAgentConfidence"
            label="Ask for a review below this confidence"
            value={policy.approvalBelowAgentConfidence}
            step="0.05"
          />
          <NumberField name="maxJobCostMicros" label="Most a single job may cost, in millionths" value={policy.maxJobCostMicros} />
        </div>
        <div className="space-y-1">
          <label htmlFor="approval" className="text-sm font-medium">
            Reviews
          </label>
          <select id="approval" name="approval" defaultValue={policy.approval} className={FIELD}>
            <option value="ALWAYS">Always ask</option>
            <option value="THRESHOLD">Ask above the thresholds</option>
            <option value="NEVER">Never ask</option>
          </select>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1">
            <label htmlFor="allowedPaths" className="text-sm font-medium">
              Allowed paths, one pattern per line
            </label>
            <textarea id="allowedPaths" name="allowedPaths" rows={4} defaultValue={policy.allowedPaths.join("\n")} className={`${FIELD} font-mono`} />
          </div>
          <div className="space-y-1">
            <label htmlFor="deniedPaths" className="text-sm font-medium">
              Denied paths, one pattern per line
            </label>
            <textarea id="deniedPaths" name="deniedPaths" rows={4} defaultValue={policy.deniedPaths.join("\n")} className={`${FIELD} font-mono`} />
          </div>
        </div>
        <div className="space-y-2">
          <Check name="requireVerified" label="Only publish patches that passed verification" checked={policy.requireVerified} />
          <Check name="allowBuildFileChanges" label="Allow changes to build files, with a review" checked={policy.allowBuildFileChanges} />
          <Check name="approvalForTextualMatches" label="Ask for a review when a match rests on plain text" checked={policy.approvalForTextualMatches} />
        </div>
      </fieldset>
      {editable ? (
        <SubmitButton tone="primary">Save as a new version</SubmitButton>
      ) : (
        <p className="text-sm text-muted">Changing the policy needs an admin key.</p>
      )}
    </form>
  );
}
