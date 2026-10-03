import { describe, expect, it } from "vitest";
import { linesOf, policyFromForm } from "./policy";

function form(overrides: Record<string, string> = {}): FormData {
  const data = new FormData();
  const base: Record<string, string> = {
    maxFilesChanged: "10",
    maxChangedLines: "400",
    allowedPaths: "src/**\n\n  docs/**  \n",
    deniedPaths: ".github/workflows/**",
    allowBuildFileChanges: "on",
    approval: "THRESHOLD",
    approvalAboveFiles: "3",
    approvalAboveLines: "80",
    approvalBelowAgentConfidence: "0.7",
    maxJobCostMicros: "2000000",
    requireVerified: "on",
  };
  for (const [name, value] of Object.entries({ ...base, ...overrides })) {
    if (value !== "") {
      data.set(name, value);
    }
  }
  return data;
}

describe("linesOf", () => {
  it("drops blanks and trims", () => {
    expect(linesOf("a\n\n  b  \n")).toEqual(["a", "b"]);
    expect(linesOf("")).toEqual([]);
  });
});

describe("policyFromForm", () => {
  it("builds the body the api expects", () => {
    expect(policyFromForm(form())).toEqual({
      maxFilesChanged: 10,
      maxChangedLines: 400,
      allowedPaths: ["src/**", "docs/**"],
      deniedPaths: [".github/workflows/**"],
      allowBuildFileChanges: true,
      requireVerified: true,
      approval: "THRESHOLD",
      approvalAboveFiles: 3,
      approvalAboveLines: 80,
      approvalBelowAgentConfidence: 0.7,
      approvalForTextualMatches: false,
      maxJobCostMicros: 2000000,
    });
  });

  it("treats unchecked boxes as false", () => {
    const body = policyFromForm(form({ allowBuildFileChanges: "", requireVerified: "" }));
    expect(body.allowBuildFileChanges).toBe(false);
    expect(body.requireVerified).toBe(false);
  });

  it("rejects values the server would refuse", () => {
    expect(() => policyFromForm(form({ approval: "SOMETIMES" }))).toThrow("approval");
    expect(() => policyFromForm(form({ approvalBelowAgentConfidence: "1.5" }))).toThrow("between 0 and 1");
    expect(() => policyFromForm(form({ maxFilesChanged: "0" }))).toThrow("at least 1");
    expect(() => policyFromForm(form({ maxChangedLines: "-4" }))).toThrow("whole number");
    expect(() => policyFromForm(form({ approvalAboveFiles: "1.5" }))).toThrow("whole number");
    expect(() => policyFromForm(form({ maxJobCostMicros: "abc" }))).toThrow("whole number");
  });
});
