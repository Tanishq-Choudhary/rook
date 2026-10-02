export function formatMicros(micros: number): string {
  const amount = micros / 1_000_000;
  if (amount === 0) {
    return "$0.00";
  }
  return amount < 0.01 ? `$${amount.toFixed(4)}` : `$${amount.toFixed(2)}`;
}

export function shortSha(sha: string | null | undefined): string {
  return sha ? sha.slice(0, 7) : "none";
}

export function formatDuration(millis: number): string {
  if (millis < 1000) {
    return `${millis} ms`;
  }
  const seconds = millis / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(1)} s`;
  }
  const minutes = Math.floor(seconds / 60);
  return `${minutes} min ${Math.round(seconds - minutes * 60)} s`;
}

export function relativeTime(then: string | null | undefined, now: Date = new Date()): string {
  if (!then) {
    return "never";
  }
  const seconds = Math.round((now.getTime() - new Date(then).getTime()) / 1000);
  if (seconds < 5) {
    return "just now";
  }
  const units: [number, string][] = [
    [86400, "day"],
    [3600, "hour"],
    [60, "minute"],
    [1, "second"],
  ];
  for (const [size, name] of units) {
    if (seconds >= size) {
      const count = Math.floor(seconds / size);
      return `${count} ${name}${count === 1 ? "" : "s"} ago`;
    }
  }
  return "just now";
}

export function words(code: string): string {
  const text = code.toLowerCase().replace(/_/g, " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}
