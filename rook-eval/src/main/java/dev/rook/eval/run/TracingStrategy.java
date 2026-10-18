package dev.rook.eval.run;

import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.strategy.Strategy;
import dev.rook.agent.strategy.StrategyOutcome;
import dev.rook.agent.workspace.Workspace;
import dev.rook.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TracingStrategy implements Strategy {

    private final Strategy delegate;
    private final Path directory;

    public TracingStrategy(Strategy delegate, Path directory) {
        this.delegate = delegate;
        this.directory = directory;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public StrategyOutcome run(AgentTask task, Workspace workspace) {
        StrategyOutcome outcome = delegate.run(task, workspace);
        if (!outcome.trace().isEmpty()) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("fixture", task.repository());
            document.put("status", outcome.status());
            document.put("verified", outcome.verified());
            document.put("changedPaths", outcome.changedPaths());
            document.put("unresolved", outcome.unresolved());
            document.put("trace", outcome.trace());
            try {
                Path file = directory.resolve(delegate.name()).resolve(task.repository() + ".json");
                Files.createDirectories(file.getParent());
                Files.writeString(file, Json.writePretty(document));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return outcome;
    }
}
