import { readFile } from "node:fs/promises";
import path from "node:path";

export interface CategoryMetrics {
  total: number;
  needingChange: number;
  accepted: number;
}

export interface StrategyMetrics {
  strategy: string;
  fixtures: number;
  needingChange: number;
  noops: number;
  impactPrecision: number;
  impactRecall: number;
  patchesProduced: number;
  accepted: number;
  patchSuccessRate: number;
  regressionEscapes: number;
  noopFixturesEdited: number;
  protectedFilesEdited: number;
  totalCostMicros: number;
  costPerAcceptedMicros: number;
  latencyP50Millis: number;
  latencyP95Millis: number;
  categories: Record<string, CategoryMetrics>;
  failures: Record<string, number>;
}

export interface EvaluationReport {
  verifier: string;
  minimumConfidence: string;
  fixtures: number;
  notRun: string[];
  strategies: StrategyMetrics[];
  limitations: string[];
}

export function parseReport(text: string): EvaluationReport {
  const raw = JSON.parse(text) as Partial<EvaluationReport>;
  if (!Array.isArray(raw.strategies) || typeof raw.fixtures !== "number") {
    throw new Error("The evaluation report is not in the expected shape");
  }
  return {
    verifier: String(raw.verifier ?? "unknown"),
    minimumConfidence: String(raw.minimumConfidence ?? "unknown"),
    fixtures: raw.fixtures,
    notRun: raw.notRun ?? [],
    strategies: raw.strategies,
    limitations: raw.limitations ?? [],
  };
}

export function evaluationDirectory(): string {
  return process.env.ROOK_EVAL_DIR ?? path.join(process.cwd(), "..", "eval", "results");
}

export async function loadReport(directory: string = evaluationDirectory()): Promise<EvaluationReport | null> {
  try {
    return parseReport(await readFile(path.join(directory, "report.json"), "utf8"));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      return null;
    }
    throw error;
  }
}

export function percent(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}
