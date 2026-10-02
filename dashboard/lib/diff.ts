export type RowKind = "add" | "del" | "ctx" | "hunk";

export interface DiffRow {
  kind: RowKind;
  oldNumber: number | null;
  newNumber: number | null;
  text: string;
}

export interface DiffFile {
  path: string;
  added: number;
  removed: number;
  rows: DiffRow[];
}

const HUNK = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

function stripPrefix(value: string): string {
  return value.startsWith("a/") || value.startsWith("b/") ? value.slice(2) : value;
}

function startsFile(lines: string[], index: number): string | null {
  const line = lines[index];
  if (line.startsWith("diff --git ")) {
    const match = /^diff --git a\/(.+) b\/(.+)$/.exec(line);
    return match ? match[2] : line.slice(11);
  }
  if (line.startsWith("--- ") && lines[index + 1]?.startsWith("+++ ") && lines[index + 2]?.startsWith("@@")) {
    const after = lines[index + 1].slice(4);
    return after === "/dev/null" ? stripPrefix(line.slice(4)) : stripPrefix(after);
  }
  return null;
}

export function parseDiff(diff: string): DiffFile[] {
  const lines = diff.split("\n");
  const files: DiffFile[] = [];
  let current: DiffFile | null = null;
  let oldNumber = 0;
  let newNumber = 0;
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    const path = startsFile(lines, index);
    if (path !== null) {
      if (line.startsWith("diff --git ") || !current || current.path !== path || current.rows.length > 0) {
        current = { path, added: 0, removed: 0, rows: [] };
        files.push(current);
      }
      if (line.startsWith("--- ")) {
        index += 1;
      }
      continue;
    }
    if (!current) {
      continue;
    }
    if (line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("index ")) {
      if (line.startsWith("+++ b/")) {
        current.path = line.slice(6);
      }
      if (current.rows.length === 0) {
        continue;
      }
    }
    const hunk = HUNK.exec(line);
    if (hunk) {
      oldNumber = Number(hunk[1]);
      newNumber = Number(hunk[2]);
      current.rows.push({ kind: "hunk", oldNumber: null, newNumber: null, text: line });
    } else if (line.startsWith("+")) {
      current.rows.push({ kind: "add", oldNumber: null, newNumber, text: line.slice(1) });
      current.added += 1;
      newNumber += 1;
    } else if (line.startsWith("-")) {
      current.rows.push({ kind: "del", oldNumber, newNumber: null, text: line.slice(1) });
      current.removed += 1;
      oldNumber += 1;
    } else if (line.startsWith(" ")) {
      current.rows.push({ kind: "ctx", oldNumber, newNumber, text: line.slice(1) });
      oldNumber += 1;
      newNumber += 1;
    }
  }
  return files;
}
