import { currentKey } from "@/lib/api";
import { apiBase } from "@/lib/config";

export const dynamic = "force-dynamic";

export async function GET(request: Request): Promise<Response> {
  const key = await currentKey();
  if (!key) {
    return new Response("Sign in first", { status: 401 });
  }
  const headers = new Headers({ Authorization: `Bearer ${key}`, Accept: "text/event-stream" });
  const last = request.headers.get("last-event-id");
  if (last) {
    headers.set("Last-Event-ID", last);
  }
  let upstream: Response;
  try {
    upstream = await fetch(`${apiBase()}/api/v1/events`, { headers, signal: request.signal, cache: "no-store" });
  } catch {
    return new Response("The Rook API could not be reached", { status: 503 });
  }
  if (!upstream.ok || !upstream.body) {
    return new Response("The event stream is not available", { status: upstream.status === 200 ? 502 : upstream.status });
  }
  return new Response(upstream.body, {
    status: 200,
    headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-store", Connection: "keep-alive" },
  });
}
