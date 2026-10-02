import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DiffViewer } from "./DiffViewer";

const DIFF = [
  "diff --git a/src/A.java b/src/A.java",
  "--- a/src/A.java",
  "+++ b/src/A.java",
  "@@ -1,2 +1,2 @@",
  " keep",
  "-old",
  "+new",
].join("\n");

describe("DiffViewer", () => {
  it("shows each file with its counts and rows", () => {
    render(<DiffViewer diff={DIFF} />);
    expect(screen.getByText("src/A.java")).toBeInTheDocument();
    expect(screen.getByText("+1")).toBeInTheDocument();
    expect(screen.getByText("-1")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Changes in src/A.java" });
    const rows = within(table).getAllByRole("row");
    expect(rows.map((row) => row.getAttribute("data-kind"))).toEqual(["hunk", "ctx", "del", "add"]);
  });

  it("says so when there is nothing to show", () => {
    render(<DiffViewer diff="" />);
    expect(screen.getByText("There is no diff.")).toBeInTheDocument();
  });
});
