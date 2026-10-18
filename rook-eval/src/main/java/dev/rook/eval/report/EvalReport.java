package dev.rook.eval.report;

import dev.rook.eval.run.FixtureResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record EvalReport(
        String verifier,
        String minimumConfidence,
        int fixtures,
        List<String> notRun,
        List<StrategyMetrics> strategies,
        List<String> limitations) {

    public static final String MODEL_RUN =
            "The llm-only and agent rows come from one run each of a single model. They say little"
                    + " about other models, and another run can differ. The agent and llm-only"
                    + " prompts include the new SDK API for the types involved.";

    public static final List<String> LIMITATIONS =
            List.of(
                    "Fixtures are generated from nine change recipes over four invented SDKs. They are small"
                            + " single module Maven projects, not real customer repositories.",
                    "Only Java SDK changes read from compiled jars are covered. OpenAPI changes are not part"
                            + " of this set.",
                    "Verification runs offline Maven on the host with the local process runner. The isolated"
                            + " Docker path is tested separately and is not used for these numbers.",
                    "No model backed strategy was run because no API keys were available. Rows for them are"
                            + " absent on purpose, not zero.");

    public EvalReport {
        notRun = List.copyOf(notRun);
        strategies = List.copyOf(strategies);
        limitations = List.copyOf(limitations);
    }

    public static EvalReport build(
            String verifier,
            String minimumConfidence,
            Map<String, List<FixtureResult>> byStrategy,
            List<String> notRun) {
        Map<String, List<FixtureResult>> ordered = new TreeMap<>(byStrategy);
        List<StrategyMetrics> metrics = new ArrayList<>();
        int fixtures = 0;
        for (Map.Entry<String, List<FixtureResult>> entry : ordered.entrySet()) {
            fixtures = Math.max(fixtures, entry.getValue().size());
            metrics.add(Metrics.of(entry.getKey(), entry.getValue()));
        }
        List<String> limitations = new ArrayList<>(LIMITATIONS);
        if (notRun.isEmpty()) {
            limitations.set(limitations.size() - 1, MODEL_RUN);
        }
        return new EvalReport(verifier, minimumConfidence, fixtures, notRun, metrics, limitations);
    }
}
