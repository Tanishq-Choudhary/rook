import { mkdtemp, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { loadReport, parseReport, percent } from "./evaluation";

const REPORT = {
  verifier: "local-maven",
  minimumConfidence: "HEURISTIC",
  fixtures: 72,
  notRun: ["llm-only"],
  strategies: [
    {
      strategy: "codemod-only",
      fixtures: 72,
      needingChange: 56,
      noops: 16,
      impactPrecision: 1,
      impactRecall: 1,
      patchesProduced: 40,
      accepted: 56,
      patchSuccessRate: 0.714,
      regressionEscapes: 0,
      noopFixturesEdited: 0,
      protectedFilesEdited: 0,
      totalCostMicros: 0,
      costPerAcceptedMicros: 0,
      latencyP50Millis: 55,
      latencyP95Millis: 506,
      categories: { "method-rename": { total: 8, needingChange: 8, accepted: 8 } },
      failures: { "no patch produced": 16 },
    },
  ],
  limitations: ["Fixtures are generated."],
};

describe("parseReport", () => {
  it("reads the report the evaluation lab writes", () => {
    const report = parseReport(JSON.stringify(REPORT));
    expect(report.fixtures).toBe(72);
    expect(report.strategies[0].categories["method-rename"].accepted).toBe(8);
    expect(report.notRun).toEqual(["llm-only"]);
  });

  it("fills missing lists and rejects a different shape", () => {
    const sparse = parseReport(JSON.stringify({ fixtures: 1, strategies: [] }));
    expect(sparse.notRun).toEqual([]);
    expect(sparse.limitations).toEqual([]);
    expect(sparse.verifier).toBe("unknown");
    expect(() => parseReport(JSON.stringify({ hello: "world" }))).toThrow("expected shape");
    expect(() => parseReport("not json")).toThrow();
  });
});

describe("loadReport", () => {
  it("returns null when no report was produced yet", async () => {
    const empty = await mkdtemp(path.join(os.tmpdir(), "rook-eval-"));
    await expect(loadReport(empty)).resolves.toBeNull();
  });

  it("loads report.json from the directory", async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), "rook-eval-"));
    await writeFile(path.join(directory, "report.json"), JSON.stringify(REPORT));
    const report = await loadReport(directory);
    expect(report?.strategies).toHaveLength(1);
  });

  it("reports a file that cannot be parsed instead of hiding it", async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), "rook-eval-"));
    await writeFile(path.join(directory, "report.json"), "{broken");
    await expect(loadReport(directory)).rejects.toThrow();
  });
});

describe("percent", () => {
  it("shows one decimal place", () => {
    expect(percent(0.714285)).toBe("71.4%");
    expect(percent(1)).toBe("100.0%");
  });
});
