import { describe, expect, it } from "vitest";
import { dollarsToMicros } from "./money";

describe("dollarsToMicros", () => {
  it("converts whole and fractional amounts", () => {
    expect(dollarsToMicros("0")).toBe(0);
    expect(dollarsToMicros("12.5")).toBe(12_500_000);
    expect(dollarsToMicros("0.000001")).toBe(1);
  });

  it("rejects blanks, negatives and text", () => {
    expect(() => dollarsToMicros("")).toThrow("zero or more");
    expect(() => dollarsToMicros("-1")).toThrow("zero or more");
    expect(() => dollarsToMicros("ten")).toThrow("zero or more");
  });
});
