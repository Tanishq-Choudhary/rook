package dev.rook.verify.gate;

import dev.rook.verify.build.RunSummary;
import dev.rook.verify.compare.Comparison;
import dev.rook.verify.compare.FlakeAnalyzer;
import java.util.List;

public record GateInput(
        String diff,
        RunSummary patched,
        Comparison comparison,
        FlakeAnalyzer.Verdict flakes,
        int maxFiles,
        int maxLines,
        List<String> includeGlobs,
        List<String> excludeGlobs,
        boolean allowBuildFiles) {

    public GateInput {
        includeGlobs = includeGlobs == null ? List.of() : List.copyOf(includeGlobs);
        excludeGlobs = excludeGlobs == null ? List.of() : List.copyOf(excludeGlobs);
    }
}
