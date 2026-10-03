import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PolicyBody } from "@/lib/policy";

vi.mock("@/app/(app)/policies/actions", () => ({ savePolicy: vi.fn() }));

import { PolicyForm } from "./PolicyForm";

const POLICY: PolicyBody = {
  maxFilesChanged: 10,
  maxChangedLines: 400,
  allowedPaths: ["src/**"],
  deniedPaths: [".github/workflows/**", "**/Dockerfile"],
  allowBuildFileChanges: false,
  requireVerified: true,
  approval: "THRESHOLD",
  approvalAboveFiles: 3,
  approvalAboveLines: 80,
  approvalBelowAgentConfidence: 0.7,
  approvalForTextualMatches: true,
  maxJobCostMicros: 2000000,
};

describe("PolicyForm", () => {
  it("fills every field from the current policy", () => {
    render(<PolicyForm installation="i-1" policy={POLICY} editable />);
    expect(screen.getByLabelText("Most files a patch may change")).toHaveValue(10);
    expect(screen.getByLabelText("Ask for a review below this confidence")).toHaveValue(0.7);
    expect(screen.getByLabelText("Reviews")).toHaveValue("THRESHOLD");
    expect(screen.getByLabelText("Denied paths, one pattern per line")).toHaveValue(".github/workflows/**\n**/Dockerfile");
    expect(screen.getByLabelText("Only publish patches that passed verification")).toBeChecked();
    expect(screen.getByLabelText("Allow changes to build files, with a review")).not.toBeChecked();
    expect(screen.getByRole("button", { name: "Save as a new version" })).toBeEnabled();
  });

  it("is read only without the right to change it", () => {
    render(<PolicyForm installation="i-1" policy={POLICY} editable={false} />);
    expect(screen.getByLabelText("Most files a patch may change")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save as a new version" })).not.toBeInTheDocument();
    expect(screen.getByText("Changing the policy needs an admin key.")).toBeInTheDocument();
  });
});
