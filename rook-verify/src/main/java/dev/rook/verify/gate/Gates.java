package dev.rook.verify.gate;

import dev.rook.agent.safety.SecretRedactor;
import dev.rook.core.security.SensitivePaths;
import dev.rook.verify.build.RunSummary;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class Gates {

    public static final String HAS_CHANGES = "has-changes";
    public static final String COMPILES = "compiles";
    public static final String TESTS_EXECUTED = "tests-executed";
    public static final String NO_NEW_FAILURES = "no-new-failures";
    public static final String TESTS_PRESERVED = "tests-preserved";
    public static final String TESTS_NOT_WEAKENED = "tests-not-weakened";
    public static final String WITHIN_LIMITS = "within-limits";
    public static final String ALLOWED_PATHS = "allowed-paths";
    public static final String NO_PROTECTED_FILES = "no-protected-files";
    public static final String NO_NEW_DEPENDENCIES = "no-new-dependencies";
    public static final String NO_SECRETS = "no-secrets";

    private final SecretRedactor redactor = new SecretRedactor();

    public GateReport evaluate(GateInput input) {
        DiffFacts facts;
        try {
            facts = DiffFacts.parse(input.diff());
        } catch (RuntimeException e) {
            return new GateReport(
                    List.of(
                            GateResult.fail(
                                    "diff-readable",
                                    "the patch could not be read: " + e.getMessage())));
        }
        DiffFacts parsed = facts;
        List<GateResult> results = new ArrayList<>();
        results.add(run(HAS_CHANGES, in -> hasChanges(parsed), input));
        results.add(run(COMPILES, in -> compiles(in), input));
        results.add(run(TESTS_EXECUTED, in -> testsExecuted(in), input));
        results.add(run(NO_NEW_FAILURES, in -> noNewFailures(in), input));
        results.add(run(TESTS_PRESERVED, in -> testsPreserved(in), input));
        results.add(run(TESTS_NOT_WEAKENED, in -> testsNotWeakened(parsed), input));
        results.add(run(WITHIN_LIMITS, in -> withinLimits(parsed, in), input));
        results.add(run(ALLOWED_PATHS, in -> allowedPaths(parsed, in), input));
        results.add(run(NO_PROTECTED_FILES, in -> noProtectedFiles(parsed, in), input));
        results.add(run(NO_NEW_DEPENDENCIES, in -> noNewDependencies(parsed), input));
        results.add(run(NO_SECRETS, in -> noSecrets(parsed), input));
        return new GateReport(results);
    }

    private GateResult run(String name, Function<GateInput, GateResult> gate, GateInput input) {
        try {
            return gate.apply(input);
        } catch (RuntimeException e) {
            return GateResult.fail(name, "the gate could not be evaluated: " + e.getMessage());
        }
    }

    private static GateResult hasChanges(DiffFacts facts) {
        if (facts.paths().isEmpty()) {
            return GateResult.fail(HAS_CHANGES, "the patch is empty");
        }
        return GateResult.pass(HAS_CHANGES);
    }

    private static GateResult compiles(GateInput input) {
        if (input.patched() == null) {
            return GateResult.fail(COMPILES, "there is no result for the patched code");
        }
        return switch (input.patched().status()) {
            case COMPILE_FAILED -> GateResult.fail(COMPILES, "the patched code does not compile");
            case BUILD_FAILED -> GateResult.fail(COMPILES, "the build failed before any test ran");
            case OUT_OF_MEMORY -> GateResult.fail(COMPILES, "the build ran out of memory");
            case TIMED_OUT -> GateResult.fail(COMPILES, "the build timed out");
            case TESTS_RAN, NO_TESTS -> GateResult.pass(COMPILES);
        };
    }

    private static GateResult testsExecuted(GateInput input) {
        RunSummary patched = input.patched();
        if (patched == null || !patched.ranTests()) {
            return GateResult.fail(
                    TESTS_EXECUTED, "no tests were executed, the change cannot be verified");
        }
        return GateResult.pass(TESTS_EXECUTED, patched.report().executed() + " tests ran");
    }

    private static GateResult noNewFailures(GateInput input) {
        if (input.comparison() == null) {
            return GateResult.fail(NO_NEW_FAILURES, "the baseline could not be compared");
        }
        List<String> confirmed = new ArrayList<>(input.comparison().regressions());
        if (input.flakes() != null) {
            confirmed.removeAll(input.flakes().flaky());
        }
        if (!confirmed.isEmpty()) {
            return GateResult.fail(
                    NO_NEW_FAILURES, "newly failing: " + String.join(", ", confirmed));
        }
        int flaky = input.flakes() == null ? 0 : input.flakes().flaky().size();
        return GateResult.pass(NO_NEW_FAILURES, flaky == 0 ? "" : flaky + " flaky tests ignored");
    }

    private static GateResult testsPreserved(GateInput input) {
        if (input.comparison() == null) {
            return GateResult.fail(TESTS_PRESERVED, "the baseline could not be compared");
        }
        if (!input.comparison().removed().isEmpty()) {
            return GateResult.fail(
                    TESTS_PRESERVED,
                    "tests disappeared: " + String.join(", ", input.comparison().removed()));
        }
        if (!input.comparison().newlySkipped().isEmpty()) {
            return GateResult.fail(
                    TESTS_PRESERVED,
                    "tests are now skipped: "
                            + String.join(", ", input.comparison().newlySkipped()));
        }
        return GateResult.pass(TESTS_PRESERVED);
    }

    private static GateResult testsNotWeakened(DiffFacts facts) {
        if (!facts.deletedTests().isEmpty()) {
            return GateResult.fail(
                    TESTS_NOT_WEAKENED,
                    "test files were deleted: " + String.join(", ", facts.deletedTests()));
        }
        if (facts.removedAssertions() > facts.addedAssertions()) {
            return GateResult.fail(
                    TESTS_NOT_WEAKENED,
                    facts.removedAssertions()
                            + " assertions were removed and only "
                            + facts.addedAssertions()
                            + " added");
        }
        return GateResult.pass(TESTS_NOT_WEAKENED);
    }

    private static GateResult withinLimits(DiffFacts facts, GateInput input) {
        if (facts.paths().size() > input.maxFiles()) {
            return GateResult.fail(
                    WITHIN_LIMITS,
                    facts.paths().size() + " files changed, the limit is " + input.maxFiles());
        }
        if (facts.changedLines() > input.maxLines()) {
            return GateResult.fail(
                    WITHIN_LIMITS,
                    facts.changedLines() + " lines changed, the limit is " + input.maxLines());
        }
        return GateResult.pass(
                WITHIN_LIMITS, facts.paths().size() + " files, " + facts.changedLines() + " lines");
    }

    private static GateResult allowedPaths(DiffFacts facts, GateInput input) {
        List<PathMatcher> include = matchers(input.includeGlobs());
        List<PathMatcher> exclude = matchers(input.excludeGlobs());
        List<String> rejected = new ArrayList<>();
        for (String path : facts.paths()) {
            Path candidate = Path.of(path);
            boolean included =
                    include.isEmpty() || include.stream().anyMatch(m -> m.matches(candidate));
            boolean excluded = exclude.stream().anyMatch(m -> m.matches(candidate));
            if (!included || excluded || SensitivePaths.isSensitive(path) || path.contains("..")) {
                rejected.add(path);
            }
        }
        if (!rejected.isEmpty()) {
            return GateResult.fail(
                    ALLOWED_PATHS, "paths outside the allowed set: " + String.join(", ", rejected));
        }
        return GateResult.pass(ALLOWED_PATHS);
    }

    private static List<PathMatcher> matchers(List<String> globs) {
        List<PathMatcher> out = new ArrayList<>();
        for (String glob : globs) {
            String normalized = glob.endsWith("/") ? glob + "**" : glob;
            out.add(FileSystems.getDefault().getPathMatcher("glob:" + normalized));
            if (!normalized.contains("*") && !normalized.contains("?")) {
                out.add(FileSystems.getDefault().getPathMatcher("glob:" + normalized + "/**"));
            }
        }
        return out;
    }

    private static GateResult noProtectedFiles(DiffFacts facts, GateInput input) {
        List<String> touched = new ArrayList<>(facts.protectedFiles());
        if (!input.allowBuildFiles()) {
            touched.addAll(facts.buildFiles());
        }
        if (!touched.isEmpty()) {
            return GateResult.fail(
                    NO_PROTECTED_FILES, "protected files changed: " + String.join(", ", touched));
        }
        return GateResult.pass(NO_PROTECTED_FILES);
    }

    private static GateResult noNewDependencies(DiffFacts facts) {
        if (!facts.addedDependencyLines().isEmpty()) {
            return GateResult.fail(
                    NO_NEW_DEPENDENCIES,
                    "dependencies were added: " + String.join(" | ", facts.addedDependencyLines()));
        }
        return GateResult.pass(NO_NEW_DEPENDENCIES);
    }

    private GateResult noSecrets(DiffFacts facts) {
        SecretRedactor.Result found =
                redactor.redactWithReport(String.join("\n", facts.addedLines()));
        if (found.changed()) {
            return GateResult.fail(
                    NO_SECRETS,
                    "the patch adds text that looks like " + String.join(", ", found.kinds()));
        }
        return GateResult.pass(NO_SECRETS);
    }
}
