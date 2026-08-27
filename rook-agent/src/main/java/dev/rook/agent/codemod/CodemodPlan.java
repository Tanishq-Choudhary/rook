package dev.rook.agent.codemod;

import dev.rook.codeintel.impact.Confidence;
import java.util.List;

public record CodemodPlan(
        String codemod, List<FileRewrite> rewrites, List<String> skipped, Confidence confidence) {

    public record FileRewrite(String path, String content, int edits) {}

    public CodemodPlan {
        rewrites = List.copyOf(rewrites);
        skipped = List.copyOf(skipped);
    }

    public int editCount() {
        return rewrites.stream().mapToInt(FileRewrite::edits).sum();
    }
}
