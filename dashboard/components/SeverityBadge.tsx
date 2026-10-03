import { words } from "@/lib/format";
import type { Tone } from "@/lib/states";

const TONE: Record<string, Tone> = {
  BREAKING: "bad",
  DEPRECATION: "warn",
  ADDITIVE: "info",
  ADVISORY: "muted",
};

const CLASSES: Record<Tone, string> = {
  good: "border-good/40 bg-good/10 text-good",
  warn: "border-warn/40 bg-warn/10 text-warn",
  bad: "border-bad/40 bg-bad/10 text-bad",
  info: "border-accent/40 bg-accent/10 text-accent",
  muted: "border-line bg-raised text-muted",
};

export function SeverityBadge({ severity }: { severity: string }) {
  const tone = TONE[severity] ?? "muted";
  return (
    <span
      data-tone={tone}
      className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium ${CLASSES[tone]}`}
    >
      {words(severity)}
    </span>
  );
}
