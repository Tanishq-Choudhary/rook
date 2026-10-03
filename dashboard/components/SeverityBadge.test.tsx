import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SeverityBadge } from "./SeverityBadge";

describe("SeverityBadge", () => {
  it("colours breaking changes as bad and deprecations as a warning", () => {
    render(
      <>
        <SeverityBadge severity="BREAKING" />
        <SeverityBadge severity="DEPRECATION" />
        <SeverityBadge severity="ADDITIVE" />
        <SeverityBadge severity="SOMETHING" />
      </>,
    );
    expect(screen.getByText("Breaking")).toHaveAttribute("data-tone", "bad");
    expect(screen.getByText("Deprecation")).toHaveAttribute("data-tone", "warn");
    expect(screen.getByText("Additive")).toHaveAttribute("data-tone", "info");
    expect(screen.getByText("Something")).toHaveAttribute("data-tone", "muted");
  });
});
