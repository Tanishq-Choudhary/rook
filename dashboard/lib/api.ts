import { cookies } from "next/headers";
import { apiBase, COOKIE } from "./config";
import { request } from "./request";

export { ApiError } from "./request";

export async function currentKey(): Promise<string | undefined> {
  return (await cookies()).get(COOKIE)?.value;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  return request<T>({ base: apiBase(), key: await currentKey() }, path, init);
}

export function query(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      search.set(name, String(value));
    }
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}
