import { describe, expect, it, vi } from "vitest";
import { ApiError, request } from "./request";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function failure(pending: Promise<unknown>): Promise<ApiError> {
  try {
    await pending;
  } catch (error) {
    return error as ApiError;
  }
  throw new Error("the request was expected to fail");
}

describe("request", () => {
  it("sends the key as a bearer token and asks for json", async () => {
    const fetcher = vi.fn(async () => json({ ok: true }));
    const result = await request<{ ok: boolean }>({ base: "http://api", key: "rk_a_b", fetcher }, "/api/v1/jobs");
    expect(result.ok).toBe(true);
    const [url, init] = fetcher.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://api/api/v1/jobs");
    const headers = new Headers(init.headers);
    expect(headers.get("Authorization")).toBe("Bearer rk_a_b");
    expect(headers.get("Accept")).toBe("application/json");
    expect(init.cache).toBe("no-store");
  });

  it("adds a content type only when there is a body", async () => {
    const fetcher = vi.fn(async () => json({}));
    await request({ base: "http://api", key: undefined, fetcher }, "/x", { method: "POST", body: "{}" });
    await request({ base: "http://api", key: undefined, fetcher }, "/x");
    const withBody = new Headers((fetcher.mock.calls[0] as unknown as [string, RequestInit])[1].headers);
    const without = new Headers((fetcher.mock.calls[1] as unknown as [string, RequestInit])[1].headers);
    expect(withBody.get("Content-Type")).toBe("application/json");
    expect(without.get("Content-Type")).toBeNull();
    expect(without.get("Authorization")).toBeNull();
  });

  it("turns a problem response into an ApiError", async () => {
    const fetcher = vi.fn(async () => json({ title: "UNAUTHORIZED", detail: "an API key is required" }, 401));
    const error = await failure(request({ base: "http://api", key: undefined, fetcher }, "/x"));
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(401);
    expect(error.title).toBe("UNAUTHORIZED");
    expect(error.message).toBe("an API key is required");
  });

  it("copes with an error body that is not json", async () => {
    const fetcher = vi.fn(async () => new Response("oops", { status: 502, statusText: "Bad Gateway" }));
    const error = await failure(request({ base: "http://api", key: "k", fetcher }, "/x"));
    expect(error.status).toBe(502);
    expect(error.message).toBe("The API answered 502");
  });

  it("reports an unreachable api as unavailable", async () => {
    const fetcher = vi.fn(async () => {
      throw new TypeError("fetch failed");
    });
    const error = await failure(request({ base: "http://api", key: "k", fetcher }, "/x"));
    expect(error.status).toBe(503);
    expect(error.title).toBe("UNAVAILABLE");
  });

  it("returns nothing for an empty success", async () => {
    const fetcher = vi.fn(async () => new Response(null, { status: 204 }));
    await expect(request({ base: "http://api", key: "k", fetcher }, "/x")).resolves.toBeUndefined();
  });
});
