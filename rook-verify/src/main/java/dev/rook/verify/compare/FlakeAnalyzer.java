package dev.rook.verify.compare;

import dev.rook.verify.build.TestCaseResult;
import dev.rook.verify.build.TestReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class FlakeAnalyzer {

    public interface Rerunner {

        TestReport rerun(Set<String> testIds);
    }

    public record Verdict(List<String> confirmed, List<String> flaky, int rerunsPerformed) {

        public Verdict {
            confirmed = List.copyOf(confirmed);
            flaky = List.copyOf(flaky);
        }
    }

    private final Rerunner rerunner;
    private final int attempts;

    public FlakeAnalyzer(Rerunner rerunner, int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("at least one rerun is needed to judge a failure");
        }
        this.rerunner = rerunner;
        this.attempts = attempts;
    }

    public Verdict analyze(List<String> suspects) {
        TreeSet<String> pending = new TreeSet<>(suspects);
        Set<String> flaky = new TreeSet<>();
        int reruns = 0;
        for (int i = 0; i < attempts && !pending.isEmpty(); i++) {
            TestReport report = rerunner.rerun(new TreeSet<>(pending));
            reruns++;
            List<String> recovered = new ArrayList<>();
            for (String id : pending) {
                TestCaseResult result = report.cases().get(id);
                if (result != null && result.outcome() == TestCaseResult.Outcome.PASSED) {
                    recovered.add(id);
                }
            }
            flaky.addAll(recovered);
            pending.removeAll(recovered);
        }
        return new Verdict(new ArrayList<>(pending), new ArrayList<>(flaky), reruns);
    }
}
