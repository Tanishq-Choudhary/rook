package dev.rook.verify.build;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record TestReport(Map<String, TestCaseResult> cases) {

    public TestReport {
        cases = Map.copyOf(new TreeMap<>(cases));
    }

    public static TestReport empty() {
        return new TestReport(Map.of());
    }

    public static TestReport of(List<TestCaseResult> results) {
        Map<String, TestCaseResult> merged = new TreeMap<>();
        for (TestCaseResult result : results) {
            merged.merge(result.id(), result, TestReport::worse);
        }
        return new TestReport(merged);
    }

    private static TestCaseResult worse(TestCaseResult a, TestCaseResult b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(TestCaseResult result) {
        return switch (result.outcome()) {
            case PASSED -> 0;
            case SKIPPED -> 1;
            case FAILED -> 2;
            case ERROR -> 3;
        };
    }

    public int executed() {
        return (int)
                cases.values().stream()
                        .filter(c -> c.outcome() != TestCaseResult.Outcome.SKIPPED)
                        .count();
    }

    public List<TestCaseResult> broken() {
        return cases.values().stream().filter(TestCaseResult::broken).toList();
    }

    public int passed() {
        return (int)
                cases.values().stream()
                        .filter(c -> c.outcome() == TestCaseResult.Outcome.PASSED)
                        .count();
    }

    public boolean isEmpty() {
        return cases.isEmpty();
    }
}
