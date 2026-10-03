export interface PolicyBody {
  maxFilesChanged: number;
  maxChangedLines: number;
  allowedPaths: string[];
  deniedPaths: string[];
  allowBuildFileChanges: boolean;
  requireVerified: boolean;
  approval: "ALWAYS" | "THRESHOLD" | "NEVER";
  approvalAboveFiles: number;
  approvalAboveLines: number;
  approvalBelowAgentConfidence: number;
  approvalForTextualMatches: boolean;
  maxJobCostMicros: number;
}

export function linesOf(text: string): string[] {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

function whole(form: FormData, name: string): number {
  const value = Number(form.get(name));
  if (!Number.isFinite(value) || value < 0 || !Number.isInteger(value)) {
    throw new Error(`${name} must be a whole number of zero or more`);
  }
  return value;
}

export function policyFromForm(form: FormData): PolicyBody {
  const approval = String(form.get("approval") ?? "");
  if (approval !== "ALWAYS" && approval !== "THRESHOLD" && approval !== "NEVER") {
    throw new Error("approval must be always, threshold or never");
  }
  const confidence = Number(form.get("approvalBelowAgentConfidence"));
  if (!Number.isFinite(confidence) || confidence < 0 || confidence > 1) {
    throw new Error("the confidence threshold must be between 0 and 1");
  }
  const files = whole(form, "maxFilesChanged");
  const lines = whole(form, "maxChangedLines");
  if (files < 1 || lines < 1) {
    throw new Error("the limits must be at least 1");
  }
  return {
    maxFilesChanged: files,
    maxChangedLines: lines,
    allowedPaths: linesOf(String(form.get("allowedPaths") ?? "")),
    deniedPaths: linesOf(String(form.get("deniedPaths") ?? "")),
    allowBuildFileChanges: form.get("allowBuildFileChanges") === "on",
    requireVerified: form.get("requireVerified") === "on",
    approval,
    approvalAboveFiles: whole(form, "approvalAboveFiles"),
    approvalAboveLines: whole(form, "approvalAboveLines"),
    approvalBelowAgentConfidence: confidence,
    approvalForTextualMatches: form.get("approvalForTextualMatches") === "on",
    maxJobCostMicros: whole(form, "maxJobCostMicros"),
  };
}
