import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Impact, ImpactLocation } from "@/lib/types";
import { ImpactGraph, layout } from "./ImpactGraph";

function location(file: string, line: number): ImpactLocation {
  return { file, line, column: 1, snippet: "x", enclosing: "A#m", referenceKind: "METHOD_CALL", confidence: "RESOLVED", test: false };
}

const IMPACT: Impact = {
  decision: "NEEDS_MIGRATION",
  summary: "two changes",
  baseSha: "a".repeat(40),
  report: {
    impacts: [
      { fingerprint: "f1", kind: "METHOD_RENAMED", severity: "BREAKING", subject: "a.B#c()", summary: "c is now d", locations: [location("src/B.java", 3), location("src/A.java", 9), location("src/A.java", 12)] },
      { fingerprint: "f2", kind: "CLASS_MOVED", severity: "BREAKING", subject: "a.B", summary: "B moved", locations: [location("src/A.java", 1)] },
    ],
  },
};

describe("layout", () => {
  it("sorts the files and counts the call sites on each edge", () => {
    const result = layout(IMPACT);
    expect(result.files).toEqual(["src/A.java", "src/B.java"]);
    expect(result.edges).toContainEqual({ from: 0, to: 0, count: 2 });
    expect(result.edges).toContainEqual({ from: 0, to: 1, count: 1 });
    expect(result.edges).toContainEqual({ from: 1, to: 0, count: 1 });
    expect(result.edges).toHaveLength(3);
  });

  it("copes with an impact without locations", () => {
    const result = layout({ ...IMPACT, report: { impacts: [] } });
    expect(result.files).toEqual([]);
    expect(result.height).toBeGreaterThan(0);
  });
});

describe("ImpactGraph", () => {
  it("draws one box per change and per file with an edge for each pair", () => {
    render(<ImpactGraph impact={IMPACT} />);
    expect(screen.getByRole("img", { name: "Which upstream changes reach which files" })).toBeInTheDocument();
    expect(screen.getAllByTestId("change")).toHaveLength(2);
    expect(screen.getAllByTestId("file")).toHaveLength(2);
    expect(screen.getAllByTestId("edge")).toHaveLength(3);
    expect(screen.getByText("c is now d")).toBeInTheDocument();
  });

  it("shortens long names", () => {
    const long: Impact = { ...IMPACT, report: { impacts: [{ ...IMPACT.report.impacts[0], summary: "x".repeat(80), locations: [location("src/" + "deep/".repeat(20) + "A.java", 1)] }] } };
    render(<ImpactGraph impact={long} />);
    expect(screen.getByText(/^x+\.\.\.$/)).toBeInTheDocument();
  });

  it("says so when nothing is affected", () => {
    render(<ImpactGraph impact={{ ...IMPACT, report: { impacts: [] } }} />);
    expect(screen.getByText("No call sites were found.")).toBeInTheDocument();
  });
});
