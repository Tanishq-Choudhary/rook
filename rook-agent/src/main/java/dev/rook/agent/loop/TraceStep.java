package dev.rook.agent.loop;

public record TraceStep(
        int sequence, String kind, String name, String detail, long millis, boolean error) {}
