export type Tone = "good" | "warn" | "bad" | "info" | "muted";

const TONES: Record<string, Tone> = {
  COMPLETED: "good",
  AWAITING_APPROVAL: "warn",
  FAILED: "bad",
  DEAD: "bad",
  REJECTED: "bad",
  PENDING: "muted",
  INDEXING: "info",
  PROPOSING: "info",
  VERIFYING: "info",
  PUBLISHING: "info",
  NO_IMPACT: "muted",
  CANCELLED: "muted",
  SUPERSEDED: "muted",
};

export function toneOf(state: string): Tone {
  return TONES[state] ?? "muted";
}

export const OPEN_STATES = ["PENDING", "INDEXING", "PROPOSING", "VERIFYING", "AWAITING_APPROVAL", "PUBLISHING"];

export const ALL_STATES = [
  ...OPEN_STATES,
  "COMPLETED",
  "NO_IMPACT",
  "REJECTED",
  "FAILED",
  "DEAD",
  "SUPERSEDED",
  "CANCELLED",
];
