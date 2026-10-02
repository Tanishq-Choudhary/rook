import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Impact, PolicyDecision, TimelineEntry, TraceStep, Verification } from "@/lib/types";
import { ImpactList } from "./ImpactList";
import { PolicyPanel } from "./PolicyPanel";
import { Timeline } from "./Timeline";
import { TracePanel } from "./TracePanel";
import { VerificationPanel } from "./VerificationPanel";

describe("Timeline", () => {
  it("lists events oldest first with their state and plain data", () => {
    const entries: TimelineEntry[] = [
      { id: 1, at: "2026-10-01T10:00:00Z", kind: "JOB_CREATED", state: "PENDING", data: {} },
      { id: 2, at: "2026-10-01T10:00:05Z", kind: "STEP_STARTED", state: "INDEXING", data: { worker: "w1", attempt: 0, nested: { a: 1 } } },
    ];
    render(<Timeline entries={entries} />);
    const items = within(screen.getByRole("list", { name: "Job timeline" })).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("Job created");
    expect(items[1]).toHaveTextContent("Step started");
    expect(items[1]).toHaveTextContent("worker w1, attempt 0");
    expect(items[1]).not.toHaveTextContent("nested");
  });

  it("says so when nothing happened", () => {
    render(<Timeline entries={[]} />);
    expect(screen.getByText("Nothing has happened yet.")).toBeInTheDocument();
  });
});

describe("VerificationPanel", () => {
  const base: Verification = {
    passed: true,
    supported: true,
    summary: "all 11 gates passed",
    baselineRun: 40,
    baselineFailed: 1,
    patchedRun: 40,
    patchedFailed: 1,
    elapsedMillis: 4500,
    report: { gates: [{ name: "compiles", passed: true, detail: "" }, { name: "limits", passed: false, detail: "too many lines" }], flaky: ["ATest#slow"] },
  };

  it("compares before and after and lists the gates", () => {
    render(<VerificationPanel verification={base} />);
    expect(screen.getByText("Passed")).toBeInTheDocument();
    expect(screen.getAllByText("40 ran, 1 failed")).toHaveLength(2);
    const gates = within(screen.getByRole("list", { name: "Verification gates" })).getAllByRole("listitem");
    expect(gates[0]).toHaveTextContent("pass");
    expect(gates[1]).toHaveTextContent("fail");
    expect(gates[1]).toHaveTextContent("too many lines");
    expect(screen.getByText("ATest#slow")).toBeInTheDocument();
  });

  it("flags new failures and an unsupported run", () => {
    render(
      <VerificationPanel
        verification={{ ...base, passed: false, baselineRun: null, baselineFailed: null, report: { regressions: ["ServiceTest#go"] } }}
      />,
    );
    expect(screen.getByText("Did not pass")).toBeInTheDocument();
    expect(screen.getByText("not run")).toBeInTheDocument();
    expect(screen.getByText("ServiceTest#go")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Verification gates" })).not.toBeInTheDocument();
  });
});

describe("PolicyPanel", () => {
  it("shows the outcome, the reasons and the review", () => {
    const decision: PolicyDecision = { outcome: "REQUIRE_APPROVAL", reasons: [{ code: "LARGE_PATCH_LINES", message: "120 lines changed" }] };
    render(
      <PolicyPanel
        decision={decision}
        approval={{ id: "a", jobId: "j", status: "APPROVED", reasons: [], requestedAt: "x", decidedBy: "key:reviewer", decidedAt: "y", note: "fine" }}
      />,
    );
    expect(screen.getByText("Require approval")).toBeInTheDocument();
    expect(screen.getByText("LARGE_PATCH_LINES")).toBeInTheDocument();
    expect(screen.getByText("Review Approved by key:reviewer: fine", { exact: false })).toBeInTheDocument();
  });

  it("works without an approval", () => {
    render(<PolicyPanel decision={{ outcome: "ALLOW", reasons: [] }} approval={null} />);
    expect(screen.getByText("Allow")).toBeInTheDocument();
  });
});

describe("ImpactList", () => {
  it("groups locations under the change they come from", () => {
    const impact: Impact = {
      decision: "NEEDS_MIGRATION",
      summary: "1 of 1 changes touch the repository",
      baseSha: "a".repeat(40),
      report: {
        impacts: [
          {
            fingerprint: "f1",
            kind: "METHOD_RENAMED",
            severity: "BREAKING",
            subject: "a.B#c()",
            summary: "c is now d",
            locations: [
              { file: "src/A.java", line: 9, column: 1, snippet: "b.c()", enclosing: "A#run", referenceKind: "METHOD_CALL", confidence: "RESOLVED", test: false },
              { file: "src/ATest.java", line: 4, column: 1, snippet: "b.c()", enclosing: "ATest#t", referenceKind: "METHOD_CALL", confidence: "HEURISTIC", test: true },
            ],
          },
        ],
      },
    };
    render(<ImpactList impact={impact} />);
    expect(screen.getByText("c is now d")).toBeInTheDocument();
    expect(screen.getByText("src/A.java:9")).toBeInTheDocument();
    expect(screen.getByText("Method call, resolved")).toBeInTheDocument();
    expect(screen.getByText("Method call, heuristic, test code")).toBeInTheDocument();
  });
});

describe("TracePanel", () => {
  it("lists the steps and marks the ones that failed", () => {
    const steps: TraceStep[] = [
      { sequence: 1, kind: "model", name: "scripted", detail: "2 tool calls", millis: 20, error: false },
      { sequence: 2, kind: "tool", name: "apply_edit", detail: "search text not found", millis: 3, error: true },
    ];
    render(<TracePanel steps={steps} />);
    const items = within(screen.getByRole("list", { name: "Agent trace" })).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[1]).toHaveTextContent("failed");
    expect(items[1]).toHaveTextContent("search text not found");
  });

  it("explains an empty trace", () => {
    render(<TracePanel steps={[]} />);
    expect(screen.getByText(/deterministic edits/)).toBeInTheDocument();
  });
});
