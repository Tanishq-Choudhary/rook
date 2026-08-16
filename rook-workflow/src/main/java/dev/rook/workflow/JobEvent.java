package dev.rook.workflow;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record JobEvent(long id, UUID jobId, Instant at, String kind, String state, JsonNode data) {}
