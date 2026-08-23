package dev.rook.agent.tools;

import dev.rook.agent.model.ToolCall;
import dev.rook.agent.model.ToolDefinition;
import dev.rook.agent.patch.PatchRejected;
import dev.rook.agent.workspace.WorkspaceViolation;
import dev.rook.core.error.RookException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ToolRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    public record Limits(Duration timeout, int maxResultChars) {

        public Limits {
            if (timeout.isNegative() || timeout.isZero() || maxResultChars < 100) {
                throw new IllegalArgumentException("invalid tool limits");
            }
        }

        public static Limits standard() {
            return new Limits(Duration.ofSeconds(30), 12_000);
        }
    }

    public interface Listener {

        Listener NOOP = (call, result) -> {};

        void called(ToolCall call, ToolResult result);
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Limits limits;
    private final Listener listener;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ToolRegistry(Limits limits, Listener listener) {
        this.limits = limits;
        this.listener = listener;
    }

    public ToolRegistry register(Tool tool) {
        ToolDefinition definition = tool.definition();
        SchemaValidator.requireSupported(definition.inputSchema());
        if (tools.putIfAbsent(definition.name(), tool) != null) {
            throw new IllegalArgumentException("tool already registered: " + definition.name());
        }
        return this;
    }

    public List<ToolDefinition> definitions() {
        List<ToolDefinition> out = new ArrayList<>();
        tools.values().forEach(tool -> out.add(tool.definition()));
        return out;
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public ToolResult execute(ToolCall call, ToolContext context) {
        long started = System.nanoTime();
        ToolResult result = run(call, context);
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        ToolResult finished = cap(result).withMillis(elapsed);
        try {
            listener.called(call, finished);
        } catch (RuntimeException e) {
            log.warn("tool listener failed for {}: {}", call.name(), e.getMessage());
        }
        return finished;
    }

    private ToolResult run(ToolCall call, ToolContext context) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.failure("unknown tool " + call.name());
        }
        List<String> problems =
                SchemaValidator.validate(tool.definition().inputSchema(), call.arguments());
        if (!problems.isEmpty()) {
            return ToolResult.failure("invalid arguments: " + String.join("; ", problems));
        }
        Future<ToolResult> future = executor.submit(() -> tool.execute(call.arguments(), context));
        try {
            return future.get(limits.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.failure(
                    call.name() + " timed out after " + limits.timeout().toSeconds() + " seconds");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ToolResult.failure(call.name() + " was interrupted");
        } catch (ExecutionException e) {
            return failure(call, e.getCause());
        }
    }

    private static ToolResult failure(ToolCall call, Throwable cause) {
        if (cause instanceof PatchRejected rejected) {
            return ToolResult.failure(
                    "edit rejected (" + rejected.reason() + "): " + rejected.getMessage());
        }
        if (cause instanceof WorkspaceViolation violation) {
            return ToolResult.failure(violation.code() + ": " + violation.getMessage());
        }
        if (cause instanceof RookException invalid) {
            return ToolResult.failure(invalid.getMessage());
        }
        log.error("tool {} failed", call.name(), cause);
        return ToolResult.failure(call.name() + " failed unexpectedly");
    }

    private ToolResult cap(ToolResult result) {
        if (result.text().length() <= limits.maxResultChars()) {
            return result;
        }
        int dropped = result.text().length() - limits.maxResultChars();
        return result.withText(
                result.text().substring(0, limits.maxResultChars())
                        + "\n[output cut, "
                        + dropped
                        + " more characters]");
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
