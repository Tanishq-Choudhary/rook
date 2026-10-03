export function dollarsToMicros(text: string): number {
  const value = Number(text);
  if (text.trim() === "" || !Number.isFinite(value) || value < 0) {
    throw new Error("Enter an amount of zero or more");
  }
  return Math.round(value * 1_000_000);
}
