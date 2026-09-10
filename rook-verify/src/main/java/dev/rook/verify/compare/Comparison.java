package dev.rook.verify.compare;

import dev.rook.verify.build.TestCaseResult;
import dev.rook.verify.build.TestReport;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public record Comparison(
        List<String> regressions,
        List<String> preExisting,
        List<String> fixed,
        List<String> added,
        List<String> removed,
        List<String> newlySkipped) {

    public Comparison {
        regressions = List.copyOf(regressions);
        preExisting = List.copyOf(preExisting);
        fixed = List.copyOf(fixed);
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        newlySkipped = List.copyOf(newlySkipped);
    }

    public static Comparison compare(TestReport baseline, TestReport patched) {
        Map<String, TestCaseResult> before = baseline.cases();
        Map<String, TestCaseResult> after = patched.cases();
        TreeSet<String> regressions = new TreeSet<>();
        TreeSet<String> preExisting = new TreeSet<>();
        TreeSet<String> fixed = new TreeSet<>();
        TreeSet<String> added = new TreeSet<>();
        TreeSet<String> removed = new TreeSet<>();
        TreeSet<String> newlySkipped = new TreeSet<>();
        for (Map.Entry<String, TestCaseResult> entry : after.entrySet()) {
            TestCaseResult old = before.get(entry.getKey());
            TestCaseResult now = entry.getValue();
            if (old == null) {
                added.add(entry.getKey());
                if (now.broken()) {
                    regressions.add(entry.getKey());
                }
                continue;
            }
            if (now.broken() && old.broken()) {
                preExisting.add(entry.getKey());
            } else if (now.broken()) {
                regressions.add(entry.getKey());
            } else if (old.broken() && now.outcome() == TestCaseResult.Outcome.PASSED) {
                fixed.add(entry.getKey());
            }
            if (now.outcome() == TestCaseResult.Outcome.SKIPPED
                    && old.outcome() == TestCaseResult.Outcome.PASSED) {
                newlySkipped.add(entry.getKey());
            }
        }
        for (Map.Entry<String, TestCaseResult> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())
                    && entry.getValue().outcome() != TestCaseResult.Outcome.SKIPPED) {
                removed.add(entry.getKey());
            }
        }
        return new Comparison(
                List.copyOf(regressions),
                List.copyOf(preExisting),
                List.copyOf(fixed),
                List.copyOf(added),
                List.copyOf(removed),
                List.copyOf(newlySkipped));
    }

    public boolean clean() {
        return regressions.isEmpty() && removed.isEmpty() && newlySkipped.isEmpty();
    }
}
