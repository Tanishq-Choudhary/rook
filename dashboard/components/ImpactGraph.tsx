import type { Impact } from "@/lib/types";

const ROW = 34;
const WIDTH = 760;
const LEFT = 16;
const RIGHT = WIDTH - 16;
const BOX = 300;

function clip(text: string, length: number): string {
  return text.length <= length ? text : `${text.slice(0, length - 1)}...`;
}

export function layout(impact: Impact) {
  const entries = impact.report.impacts ?? [];
  const files: string[] = [];
  for (const entry of entries) {
    for (const location of entry.locations) {
      if (!files.includes(location.file)) {
        files.push(location.file);
      }
    }
  }
  files.sort();
  const edges: { from: number; to: number; count: number }[] = [];
  entries.forEach((entry, from) => {
    const counts = new Map<number, number>();
    for (const location of entry.locations) {
      const to = files.indexOf(location.file);
      counts.set(to, (counts.get(to) ?? 0) + 1);
    }
    counts.forEach((count, to) => edges.push({ from, to, count }));
  });
  const height = Math.max(entries.length, files.length, 1) * ROW + 16;
  return { entries, files, edges, height };
}

export function ImpactGraph({ impact }: { impact: Impact }) {
  const { entries, files, edges, height } = layout(impact);
  if (entries.length === 0) {
    return <p className="text-sm text-muted">No call sites were found.</p>;
  }
  const y = (index: number) => 8 + index * ROW + ROW / 2;
  return (
    <svg
      role="img"
      aria-label="Which upstream changes reach which files"
      viewBox={`0 0 ${WIDTH} ${height}`}
      className="w-full"
    >
      {edges.map((edge) => (
        <line
          key={`${edge.from}-${edge.to}`}
          data-testid="edge"
          x1={LEFT + BOX}
          y1={y(edge.from)}
          x2={RIGHT - BOX}
          y2={y(edge.to)}
          stroke="var(--accent)"
          strokeWidth={Math.min(1 + edge.count, 5)}
          strokeOpacity={0.55}
        />
      ))}
      {entries.map((entry, index) => (
        <g key={entry.fingerprint} data-testid="change">
          <rect x={LEFT} y={y(index) - 13} width={BOX} height={26} rx={5} fill="var(--surface-raised)" stroke="var(--border)" />
          <text x={LEFT + 8} y={y(index) + 4} fontSize={12} fill="var(--text)">
            {clip(entry.summary, 44)}
          </text>
        </g>
      ))}
      {files.map((file, index) => (
        <g key={file} data-testid="file">
          <rect x={RIGHT - BOX} y={y(index) - 13} width={BOX} height={26} rx={5} fill="var(--surface-raised)" stroke="var(--border)" />
          <text x={RIGHT - BOX + 8} y={y(index) + 4} fontSize={12} fontFamily="ui-monospace, monospace" fill="var(--text)">
            {clip(file, 42)}
          </text>
        </g>
      ))}
    </svg>
  );
}
