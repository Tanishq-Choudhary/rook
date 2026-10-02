import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Pager, pageHref } from "./Pager";

describe("pageHref", () => {
  it("keeps the filters and drops the first page number", () => {
    expect(pageHref("/jobs", { state: "FAILED" }, 0)).toBe("/jobs?state=FAILED");
    expect(pageHref("/jobs", { state: "FAILED" }, 2)).toBe("/jobs?state=FAILED&page=2");
    expect(pageHref("/jobs", { state: undefined }, 0)).toBe("/jobs");
  });
});

describe("Pager", () => {
  it("offers next on the first page and previous on the last", () => {
    const { rerender } = render(<Pager page={{ items: [], page: 0, size: 10, total: 25 }} path="/jobs" params={{}} />);
    expect(screen.getByText("25 in total, page 1 of 3")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Next" })).toHaveAttribute("href", "/jobs?page=1");
    expect(screen.queryByRole("link", { name: "Previous" })).not.toBeInTheDocument();
    rerender(<Pager page={{ items: [], page: 2, size: 10, total: 25 }} path="/jobs" params={{ state: "FAILED" }} />);
    expect(screen.getByRole("link", { name: "Previous" })).toHaveAttribute("href", "/jobs?state=FAILED&page=1");
    expect(screen.queryByRole("link", { name: "Next" })).not.toBeInTheDocument();
  });

  it("shows one page when it is empty", () => {
    render(<Pager page={{ items: [], page: 0, size: 10, total: 0 }} path="/jobs" params={{}} />);
    expect(screen.getByText("0 in total, page 1 of 1")).toBeInTheDocument();
  });
});
