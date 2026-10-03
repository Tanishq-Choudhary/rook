"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { words } from "@/lib/format";
import type { TimelineEntry } from "@/lib/types";

export interface LiveEvent extends TimelineEntry {
  jobId: string;
}

const LIMIT = 10;

export function addEvent(events: LiveEvent[], next: LiveEvent): LiveEvent[] {
  if (events.some((event) => event.id === next.id)) {
    return events;
  }
  return [next, ...events].slice(0, LIMIT);
}

export function LiveFeed({ source = "/events" }: { source?: string }) {
  const [events, setEvents] = useState<LiveEvent[]>([]);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    const stream = new EventSource(source);
    stream.onopen = () => setConnected(true);
    stream.onerror = () => setConnected(false);
    stream.addEventListener("job", (message) => {
      try {
        const event = JSON.parse((message as MessageEvent<string>).data) as LiveEvent;
        setEvents((current) => addEvent(current, event));
      } catch {
        return;
      }
    });
    return () => stream.close();
  }, [source]);

  return (
    <div>
      <p className="mb-2 flex items-center gap-2 text-xs text-muted">
        <span
          aria-hidden="true"
          className={`inline-block h-2 w-2 rounded-full ${connected ? "bg-good" : "bg-line"}`}
        />
        {connected ? "Live" : "Connecting"}
      </p>
      {events.length === 0 ? (
        <p className="text-sm text-muted">Waiting for activity.</p>
      ) : (
        <ul aria-live="polite" aria-label="Live job events" className="space-y-1 text-sm">
          {events.map((event) => (
            <li key={event.id} className="flex flex-wrap items-baseline gap-2">
              <Link href={`/jobs/${event.jobId}`} className="font-medium hover:underline">
                {words(event.kind)}
              </Link>
              {event.state ? <span className="text-xs text-muted">{words(event.state)}</span> : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
