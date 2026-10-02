export class ApiError extends Error {
  readonly status: number;
  readonly title: string;

  constructor(status: number, title: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.title = title;
  }
}

export interface Transport {
  base: string;
  key: string | undefined;
  fetcher?: typeof fetch;
}

export async function request<T>(transport: Transport, path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (transport.key) {
    headers.set("Authorization", `Bearer ${transport.key}`);
  }
  if (init.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const send = transport.fetcher ?? fetch;
  let response: Response;
  try {
    response = await send(`${transport.base}${path}`, { ...init, headers, cache: "no-store" });
  } catch {
    throw new ApiError(503, "UNAVAILABLE", "The Rook API could not be reached");
  }
  if (!response.ok) {
    let title = response.statusText || "ERROR";
    let detail = `The API answered ${response.status}`;
    try {
      const problem = (await response.json()) as { title?: string; detail?: string };
      title = problem.title ?? title;
      detail = problem.detail ?? detail;
    } catch {
      detail = `The API answered ${response.status}`;
    }
    throw new ApiError(response.status, title, detail);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
