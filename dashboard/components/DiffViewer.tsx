import { parseDiff, type RowKind } from "@/lib/diff";

const ROW: Record<RowKind, string> = {
  add: "bg-good/10",
  del: "bg-bad/10",
  ctx: "",
  hunk: "bg-raised text-muted",
};

const MARK: Record<RowKind, string> = { add: "+", del: "-", ctx: " ", hunk: "" };

export function DiffViewer({ diff }: { diff: string }) {
  const files = parseDiff(diff);
  if (files.length === 0) {
    return <p className="text-sm text-muted">There is no diff.</p>;
  }
  return (
    <div className="space-y-4">
      {files.map((file) => (
        <figure key={file.path} className="overflow-hidden rounded-md border border-line">
          <figcaption className="flex items-center justify-between border-b border-line bg-raised px-3 py-2 text-sm">
            <span className="font-mono">{file.path}</span>
            <span className="text-xs text-muted">
              <span className="text-good">+{file.added}</span> <span className="text-bad">-{file.removed}</span>
            </span>
          </figcaption>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse font-mono text-xs" aria-label={`Changes in ${file.path}`}>
              <tbody>
                {file.rows.map((row, index) => (
                  <tr key={index} data-kind={row.kind} className={ROW[row.kind]}>
                    <td className="w-10 select-none px-2 text-right text-muted">{row.oldNumber ?? ""}</td>
                    <td className="w-10 select-none px-2 text-right text-muted">{row.newNumber ?? ""}</td>
                    <td className="whitespace-pre px-2">
                      {row.kind === "hunk" ? row.text : `${MARK[row.kind]} ${row.text}`}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </figure>
      ))}
    </div>
  );
}
