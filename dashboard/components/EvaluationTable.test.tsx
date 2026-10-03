import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { EvaluationReport } from "@/lib/evaluation";
import { EvaluationTable } from "./EvaluationTable";

const REPORT: EvaluationReport = {
  verifier: "local-maven",
  minimumConfidence: "HEURISTIC",
  fixtures: 72,
  notRun: ["llm-only", "agent"],
  limitations: ["Fixtures are generated.", "No model backed strategy was run."],
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
      protectedFilesEdited: 1,
      totalCostMicros: 0,
      costPerAcceptedMicros: 0,
      latencyP50Millis: 55,
      latencyP95Millis: 506,
      categories: { "method-rename": { total: 8, needingChange: 8, accepted: 8 }, "return-type-changed": { total: 8, needingChange: 8, accepted: 0 } },
      failures: {},
    },
  ],
};

describe("EvaluationTable", () => {
  it("shows the headline numbers of each strategy", () => {
    render(<EvaluationTable report={REPORT} />);
    const table = screen.getByRole("table", { name: "Strategy results" });
    const row = within(table).getByRole("row", { name: /codemod-only/ });
    expect(row).toHaveTextContent("71.4% (56 of 72)");
    expect(row).toHaveTextContent("$0.00");
    expect(row).toHaveTextContent("55 ms");
    expect(within(row).getAllByRole("cell")[2]).toHaveTextContent("1");
  });

  it("breaks the result down by kind of change and states what was not run", () => {
    render(<EvaluationTable report={REPORT} />);
    expect(screen.getByText("Method rename")).toBeInTheDocument();
    expect(screen.getByText("Return type changed")).toBeInTheDocument();
    expect(screen.getByText("8 of 8")).toBeInTheDocument();
    expect(screen.getByText("0 of 8")).toBeInTheDocument();
    expect(screen.getByText(/Not run: llm-only, agent/)).toBeInTheDocument();
    expect(screen.getByText("No model backed strategy was run.")).toBeInTheDocument();
    expect(screen.getByText(/Impact precision 1.000, recall 1.000/)).toBeInTheDocument();
  });
});
