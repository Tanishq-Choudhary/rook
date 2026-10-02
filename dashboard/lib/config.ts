export const COOKIE = "rook_key";

export function apiBase(): string {
  return (process.env.ROOK_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");
}
