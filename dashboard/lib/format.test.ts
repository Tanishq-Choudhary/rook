import { describe, expect, it } from "vitest";
import { formatDuration, formatMicros, relativeTime, shortSha, words } from "./format";
import { toneOf } from "./states";

describe("formatMicros", () => {
  it("shows whole cents above a cent and four places below", () => {
    expect(formatMicros(0)).toBe("$0.00");
    expect(formatMicros(1_500_000)).toBe("$1.50");
    expect(formatMicros(120)).toBe("$0.0001");
    expect(formatMicros(10_000)).toBe("$0.01");
  });
});

describe("shortSha", () => {
  it("cuts to seven characters and handles a missing value", () => {
    expect(shortSha("0123456789abcdef")).toBe("0123456");
    expect(shortSha(null)).toBe("none");
    expect(shortSha(undefined)).toBe("none");
  });
});

describe("formatDuration", () => {
  it("picks a unit that fits", () => {
    expect(formatDuration(250)).toBe("250 ms");
    expect(formatDuration(2500)).toBe("2.5 s");
    expect(formatDuration(125_000)).toBe("2 min 5 s");
  });
});

describe("relativeTime", () => {
  const now = new Date("2026-10-01T12:00:00Z");
  it("describes how long ago something happened", () => {
    expect(relativeTime("2026-10-01T11:59:58Z", now)).toBe("just now");
    expect(relativeTime("2026-10-01T11:59:30Z", now)).toBe("30 seconds ago");
    expect(relativeTime("2026-10-01T11:59:00Z", now)).toBe("1 minute ago");
    expect(relativeTime("2026-10-01T09:00:00Z", now)).toBe("3 hours ago");
    expect(relativeTime("2026-09-29T12:00:00Z", now)).toBe("2 days ago");
  });
  it("copes with a missing time", () => {
    expect(relativeTime(null, now)).toBe("never");
  });
});

describe("words", () => {
  it("turns a code into a sentence start", () => {
    expect(words("AWAITING_APPROVAL")).toBe("Awaiting approval");
    expect(words("PR")).toBe("Pr");
  });
});

describe("toneOf", () => {
  it("maps job states to a tone and falls back to muted", () => {
    expect(toneOf("COMPLETED")).toBe("good");
    expect(toneOf("AWAITING_APPROVAL")).toBe("warn");
    expect(toneOf("FAILED")).toBe("bad");
    expect(toneOf("VERIFYING")).toBe("info");
    expect(toneOf("SOMETHING_NEW")).toBe("muted");
  });
});
