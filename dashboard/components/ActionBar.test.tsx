import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/app/(app)/jobs/[id]/actions", () => ({
  approve: vi.fn(),
  reject: vi.fn(),
  cancel: vi.fn(),
  retry: vi.fn(),
}));

import { ActionBar } from "./ActionBar";

describe("ActionBar", () => {
  it("lets a reviewer approve or reject a waiting job and cancel it", () => {
    render(<ActionBar id="job-1" state="AWAITING_APPROVAL" />);
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
    expect(screen.getByLabelText("Review note")).toHaveAttribute("maxlength", "500");
    expect(screen.getByRole("button", { name: "Cancel job" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Retry/ })).not.toBeInTheDocument();
  });

  it("offers a retry for failed work and nothing for finished work", () => {
    const { rerender, container } = render(<ActionBar id="job-1" state="FAILED" />);
    expect(screen.getByRole("button", { name: "Retry from the start" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    rerender(<ActionBar id="job-1" state="COMPLETED" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("only allows cancelling while a job is running", () => {
    render(<ActionBar id="job-1" state="VERIFYING" />);
    expect(screen.getByRole("button", { name: "Cancel job" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });
});
