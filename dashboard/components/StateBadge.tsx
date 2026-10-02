import { toneOf, type Tone } from "@/lib/states";
import { words } from "@/lib/format";

const CLASSES: Record<Tone, string> = {
  good: "border-good/40 bg-good/10 text-good",
  warn: "border-warn/40 bg-warn/10 text-warn",
  bad: "border-bad/40 bg-bad/10 text-bad",
  info: "border-accent/40 bg-accent/10 text-accent",
  muted: "border-line bg-raised text-muted",
};

export function StateBadge({ state }: { state: string }) {
  return (
    <span
      data-tone={toneOf(state)}
      className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium ${CLASSES[toneOf(state)]}`}
    >
      {words(state)}
    </span>
  );
}
