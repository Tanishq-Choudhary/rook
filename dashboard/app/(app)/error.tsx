"use client";

export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div role="alert" className="rounded-lg border border-bad/40 bg-bad/10 p-4">
      <h1 className="mb-1 text-base font-semibold text-bad">Something went wrong</h1>
      <p className="mb-3 text-sm">{error.message || "The page could not be loaded."}</p>
      <button type="button" onClick={reset} className="rounded-md border border-line bg-surface px-3 py-1.5 text-sm">
        Try again
      </button>
    </div>
  );
}
