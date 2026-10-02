import { describe, expect, it } from "vitest";
import { parseDiff } from "./diff";

const SAMPLE = [
  "diff --git a/src/A.java b/src/A.java",
  "index 111..222 100644",
  "--- a/src/A.java",
  "+++ b/src/A.java",
  "@@ -3,4 +3,5 @@ class A {",
  " keep",
  "-old line",
  "+new line",
  "+another",
  " tail",
  "diff --git a/src/B.java b/src/B.java",
  "--- a/src/B.java",
  "+++ b/src/B.java",
  "@@ -1 +1 @@",
  "-x",
  "+y",
].join("\n");

describe("parseDiff", () => {
  it("splits a diff into files with counts", () => {
    const files = parseDiff(SAMPLE);
    expect(files.map((f) => f.path)).toEqual(["src/A.java", "src/B.java"]);
    expect(files[0]).toMatchObject({ added: 2, removed: 1 });
    expect(files[1]).toMatchObject({ added: 1, removed: 1 });
  });

  it("numbers old and new lines from the hunk header", () => {
    const rows = parseDiff(SAMPLE)[0].rows;
    expect(rows[0]).toMatchObject({ kind: "hunk" });
    expect(rows[1]).toEqual({ kind: "ctx", oldNumber: 3, newNumber: 3, text: "keep" });
    expect(rows[2]).toEqual({ kind: "del", oldNumber: 4, newNumber: null, text: "old line" });
    expect(rows[3]).toEqual({ kind: "add", oldNumber: null, newNumber: 4, text: "new line" });
    expect(rows[4]).toEqual({ kind: "add", oldNumber: null, newNumber: 5, text: "another" });
    expect(rows[5]).toEqual({ kind: "ctx", oldNumber: 5, newNumber: 6, text: "tail" });
  });

  it("copes with an empty diff and with text before the first file", () => {
    expect(parseDiff("")).toEqual([]);
    expect(parseDiff("noise\nmore noise")).toEqual([]);
  });

  it("keeps lines that look like headers inside a hunk", () => {
    const files = parseDiff(
      ["diff --git a/f b/f", "--- a/f", "+++ b/f", "@@ -1 +1 @@", "-a", "+b"].join("\n"),
    );
    expect(files[0].rows.filter((r) => r.kind === "add")).toHaveLength(1);
  });

  it("handles a single line hunk header without counts", () => {
    const files = parseDiff(["diff --git a/f b/f", "@@ -7 +9 @@", " x"].join("\n"));
    expect(files[0].rows[1]).toMatchObject({ oldNumber: 7, newNumber: 9 });
  });

  it("reads the plain form the server produces, without git headers", () => {
    const plain = [
      "--- a/src/A.java",
      "+++ b/src/A.java",
      "@@ -8,2 +8,2 @@",
      " keep",
      "-return client.charge(5);",
      "+return client.createCharge(5);",
      "--- a/src/B.java",
      "+++ b/src/B.java",
      "@@ -1 +1 @@",
      "-x",
      "+y",
    ].join("\n");
    const files = parseDiff(plain);
    expect(files.map((f) => f.path)).toEqual(["src/A.java", "src/B.java"]);
    expect(files[0].rows[2]).toEqual({ kind: "del", oldNumber: 9, newNumber: null, text: "return client.charge(5);" });
    expect(files[1].added).toBe(1);
  });

  it("names a created file by its new path", () => {
    const created = ["--- /dev/null", "+++ b/src/New.java", "@@ -0,0 +1 @@", "+class New {}"].join("\n");
    const files = parseDiff(created);
    expect(files[0].path).toBe("src/New.java");
    expect(files[0].added).toBe(1);
  });
});
