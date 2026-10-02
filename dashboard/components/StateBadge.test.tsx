import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StateBadge } from "./StateBadge";

describe("StateBadge", () => {
  it("shows the state in words with its tone", () => {
    render(<StateBadge state="AWAITING_APPROVAL" />);
    const badge = screen.getByText("Awaiting approval");
    expect(badge).toHaveAttribute("data-tone", "warn");
  });

  it("marks failures as bad and finished work as good", () => {
    render(
      <>
        <StateBadge state="FAILED" />
        <StateBadge state="COMPLETED" />
      </>,
    );
    expect(screen.getByText("Failed")).toHaveAttribute("data-tone", "bad");
    expect(screen.getByText("Completed")).toHaveAttribute("data-tone", "good");
  });
});
