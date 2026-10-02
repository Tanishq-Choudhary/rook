import { approve, cancel, reject, retry } from "@/app/(app)/jobs/[id]/actions";
import { OPEN_STATES } from "@/lib/states";
import { SubmitButton } from "./SubmitButton";

export function ActionBar({ id, state }: { id: string; state: string }) {
  const reviewing = state === "AWAITING_APPROVAL";
  const open = OPEN_STATES.includes(state);
  const retryable = state === "FAILED" || state === "DEAD";
  if (!reviewing && !open && !retryable) {
    return null;
  }
  return (
    <div className="space-y-3 rounded-lg border border-line bg-raised p-4">
      {reviewing ? (
        <form action={approve.bind(null, id)} className="space-y-2">
          <label htmlFor="note" className="text-sm font-medium">
            Review note
          </label>
          <textarea
            id="note"
            name="note"
            rows={2}
            maxLength={500}
            className="w-full rounded-md border border-line bg-surface px-3 py-2 text-sm"
          />
          <div className="flex gap-2">
            <SubmitButton tone="primary">Approve</SubmitButton>
            <SubmitButton tone="danger" formAction={reject.bind(null, id)}>
              Reject
            </SubmitButton>
          </div>
        </form>
      ) : null}
      <div className="flex gap-2">
        {open ? (
          <form action={cancel.bind(null, id)}>
            <SubmitButton tone="neutral">Cancel job</SubmitButton>
          </form>
        ) : null}
        {retryable ? (
          <form action={retry.bind(null, id)}>
            <SubmitButton tone="primary">Retry from the start</SubmitButton>
          </form>
        ) : null}
      </div>
    </div>
  );
}
