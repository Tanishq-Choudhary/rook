import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { addEvent, LiveFeed, type LiveEvent } from "./LiveFeed";

class FakeSource {
  static instances: FakeSource[] = [];
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  private listeners = new Map<string, ((message: MessageEvent<string>) => void)[]>();

  constructor(readonly url: string) {
    FakeSource.instances.push(this);
  }

  addEventListener(name: string, listener: (message: MessageEvent<string>) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), listener]);
  }

  emit(name: string, data: string) {
    for (const listener of this.listeners.get(name) ?? []) {
      listener({ data } as MessageEvent<string>);
    }
  }

  close() {
    this.closed = true;
  }
}

function event(id: number, kind = "STEP_STARTED"): LiveEvent {
  return { id, jobId: `job-${id}`, at: "2026-10-01T10:00:00Z", kind, state: "INDEXING", data: {} };
}

describe("addEvent", () => {
  it("puts the newest first, ignores repeats and keeps ten", () => {
    let events: LiveEvent[] = [];
    for (let id = 1; id <= 12; id += 1) {
      events = addEvent(events, event(id));
    }
    expect(events).toHaveLength(10);
    expect(events[0].id).toBe(12);
    expect(addEvent(events, event(12))).toBe(events);
  });
});

describe("LiveFeed", () => {
  beforeEach(() => {
    FakeSource.instances = [];
    vi.stubGlobal("EventSource", FakeSource);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("connects, shows events as they arrive and closes on unmount", () => {
    const { unmount } = render(<LiveFeed />);
    const source = FakeSource.instances[0];
    expect(source.url).toBe("/events");
    expect(screen.getByText("Connecting")).toBeInTheDocument();
    expect(screen.getByText("Waiting for activity.")).toBeInTheDocument();
    act(() => source.onopen?.());
    expect(screen.getByText("Live")).toBeInTheDocument();
    act(() => source.emit("job", JSON.stringify(event(7, "PATCH_PROPOSED"))));
    const link = screen.getByRole("link", { name: "Patch proposed" });
    expect(link).toHaveAttribute("href", "/jobs/job-7");
    act(() => source.onerror?.());
    expect(screen.getByText("Connecting")).toBeInTheDocument();
    unmount();
    expect(source.closed).toBe(true);
  });

  it("ignores messages that are not json", () => {
    render(<LiveFeed />);
    act(() => FakeSource.instances[0].emit("job", "not json"));
    expect(screen.getByText("Waiting for activity.")).toBeInTheDocument();
  });
});
