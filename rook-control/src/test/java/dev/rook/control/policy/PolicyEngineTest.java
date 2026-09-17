package dev.rook.control.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.codeintel.impact.Confidence;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private static final Policy STANDARD = Policy.standard();

    private static PolicyInput small(String... paths) {
        return new PolicyInput(List.of(paths), 12, 0.9, true, 1000, Confidence.RESOLVED);
    }

    private static Decision decide(Policy policy, PolicyInput input) {
        return PolicyEngine.evaluate(policy, input);
    }

    @Test
    void aSmallVerifiedResolvedPatchIsAllowed() {
        Decision decision = decide(STANDARD, small("src/main/java/app/Service.java"));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    void anUnverifiedPatchIsDeniedUnlessThePolicyDoesNotCare() {
        PolicyInput input =
                new PolicyInput(List.of("a/B.java"), 5, 0.9, false, 0, Confidence.RESOLVED);
        assertThat(decide(STANDARD, input).has("UNVERIFIED")).isTrue();
        assertThat(decide(STANDARD, input).denied()).isTrue();
        Policy relaxed =
                new Policy(
                        10,
                        400,
                        List.of(),
                        List.of(),
                        false,
                        false,
                        ApprovalMode.NEVER,
                        3,
                        80,
                        0.7,
                        true,
                        2_000_000);
        assertThat(decide(relaxed, input).allowed()).isTrue();
    }

    @Test
    void limitsOnFilesLinesAndCostDeny() {
        PolicyInput many =
                new PolicyInput(
                        List.of("a/1.java", "a/2.java", "a/3.java"),
                        500,
                        0.9,
                        true,
                        5_000_000,
                        Confidence.RESOLVED);
        Decision decision = decide(STANDARD.withLimits(2, 400), many);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.has("TOO_MANY_FILES")).isTrue();
        assertThat(decision.has("TOO_MANY_LINES")).isTrue();
        assertThat(decision.has("COST_OVER_CAP")).isTrue();
    }

    @Test
    void emptyPatchesAreDenied() {
        PolicyInput none = new PolicyInput(List.of(), 0, 1, true, 0, Confidence.RESOLVED);
        assertThat(decide(STANDARD, none).has("EMPTY_PATCH")).isTrue();
    }

    @Test
    void secretsWorkflowsAndRookConfigNeverPass() {
        for (String path :
                List.of(
                        ".env",
                        "config/.env.production",
                        "keys/server.pem",
                        ".github/workflows/ci.yml",
                        ".rook.yml",
                        "infra/prod.tfvars")) {
            Decision decision = decide(STANDARD.withPaths(List.of(), List.of()), small(path));
            assertThat(decision.denied()).as(path).isTrue();
            assertThat(decision.has("SENSITIVE_PATH") || decision.has("PROTECTED_PATH"))
                    .as(path)
                    .isTrue();
        }
    }

    @Test
    void deniedAndAllowedGlobsAreHonoured() {
        Policy policy = STANDARD.withPaths(List.of("src/**"), List.of("src/legacy/**"));
        assertThat(decide(policy, small("src/main/A.java")).allowed()).isTrue();
        assertThat(decide(policy, small("src/legacy/B.java")).has("DENIED_PATH")).isTrue();
        assertThat(decide(policy, small("docs/C.java")).has("PATH_NOT_ALLOWED")).isTrue();
        assertThat(decide(STANDARD, small("tools/run.sh")).has("DENIED_PATH")).isTrue();
    }

    @Test
    void buildFilesAreDeniedUnlessAllowedAndThenReviewed() {
        assertThat(decide(STANDARD, small("pom.xml")).has("BUILD_FILE_DENIED")).isTrue();
        Decision reviewed = decide(STANDARD.allowingBuildFiles(), small("module/pom.xml"));
        assertThat(reviewed.needsApproval()).isTrue();
        assertThat(reviewed.has("BUILD_FILE_CHANGE")).isTrue();
    }

    @Test
    void thresholdsAskForAReview() {
        PolicyInput wide =
                new PolicyInput(
                        List.of("a/1.java", "a/2.java", "a/3.java", "a/4.java"),
                        120,
                        0.4,
                        true,
                        0,
                        Confidence.TEXTUAL);
        Decision decision = decide(STANDARD, wide);
        assertThat(decision.needsApproval()).isTrue();
        assertThat(decision.reasons())
                .extracting(Reason::code)
                .containsExactlyInAnyOrder(
                        "LARGE_PATCH_FILES",
                        "LARGE_PATCH_LINES",
                        "LOW_CONFIDENCE",
                        "TEXTUAL_MATCH");
    }

    @Test
    void approvalModesOverrideTheThresholds() {
        PolicyInput wide =
                new PolicyInput(
                        List.of("a/1.java", "a/2.java", "a/3.java", "a/4.java"),
                        500,
                        0.1,
                        true,
                        0,
                        Confidence.TEXTUAL);
        Policy loose = STANDARD.withLimits(10, 1000).withApproval(ApprovalMode.NEVER);
        assertThat(decide(loose, wide).allowed()).isTrue();
        Policy strict = STANDARD.withApproval(ApprovalMode.ALWAYS);
        Decision always = decide(strict, small("a/B.java"));
        assertThat(always.needsApproval()).isTrue();
        assertThat(always.has("APPROVAL_REQUIRED")).isTrue();
    }

    @Test
    void aDenialBeatsAReviewRequest() {
        PolicyInput input =
                new PolicyInput(List.of("pom.xml"), 500, 0.1, true, 0, Confidence.TEXTUAL);
        Decision decision = decide(STANDARD.withLimits(10, 1000), input);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.has("LOW_CONFIDENCE")).isFalse();
    }

    @Test
    void policiesRejectNonsense() {
        assertThatThrownBy(() -> STANDARD.withLimits(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> STANDARD.withCostCap(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new Policy(1, 1, null, null, false, true, null, 0, 0, 1.5, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        Policy defaults = new Policy(1, 1, null, null, false, true, null, 0, 0, 0.5, false, 0);
        assertThat(defaults.approval()).isEqualTo(ApprovalMode.THRESHOLD);
        assertThat(defaults.allowedPaths()).isEmpty();
    }

    @Provide
    Arbitrary<PolicyInput> inputs() {
        Arbitrary<String> paths =
                Arbitraries.of(
                        "src/A.java",
                        "src/b/B.java",
                        "pom.xml",
                        ".env",
                        ".github/workflows/x.yml",
                        "docs/readme.txt",
                        "run.sh");
        return Arbitraries.integers()
                .between(0, 700)
                .flatMap(
                        lines ->
                                paths.list()
                                        .ofMaxSize(14)
                                        .flatMap(
                                                list ->
                                                        Arbitraries.doubles()
                                                                .between(0, 1)
                                                                .map(
                                                                        confidence ->
                                                                                new PolicyInput(
                                                                                        list,
                                                                                        lines,
                                                                                        confidence,
                                                                                        lines % 2
                                                                                                == 0,
                                                                                        lines * 10L,
                                                                                        Confidence
                                                                                                .values()[
                                                                                                lines
                                                                                                        % 3]))));
    }

    @Property
    void protectedPathsAreNeverAllowedWhateverElseHolds(@ForAll("inputs") PolicyInput input) {
        boolean touchesProtected =
                input.changedPaths().stream()
                        .anyMatch(p -> p.equals(".env") || p.startsWith(".github/workflows/"));
        Decision decision = decide(STANDARD.withApproval(ApprovalMode.NEVER), input);
        if (touchesProtected) {
            assertThat(decision.denied()).isTrue();
        }
        if (decision.allowed()) {
            assertThat(input.verified()).isTrue();
            assertThat(input.changedPaths()).isNotEmpty();
        }
    }

    @Property
    void evaluationIsDeterministic(@ForAll("inputs") PolicyInput input) {
        assertThat(decide(STANDARD, input)).isEqualTo(decide(STANDARD, input));
    }

    @Test
    void globsUnderstandStarsAndDoubleStars() {
        assertThat(Glob.matches("src/*.java", "src/A.java")).isTrue();
        assertThat(Glob.matches("src/*.java", "src/x/A.java")).isFalse();
        assertThat(Glob.matches("src/**", "src/x/y/A.java")).isTrue();
        assertThat(Glob.matches("**/Dockerfile", "Dockerfile")).isTrue();
        assertThat(Glob.matches("**/Dockerfile", "a/b/Dockerfile")).isTrue();
        assertThat(Glob.matches("a/?.txt", "a/b.txt")).isTrue();
        assertThat(Glob.matches("a/?.txt", "a/bc.txt")).isFalse();
        assertThat(Glob.matches("a.b", "aXb")).isFalse();
        assertThat(Glob.matches(".github/workflows/**", ".github/workflows/ci.yml")).isTrue();
        assertThat(Glob.matches("./src/**", "src/A.java")).isTrue();
    }
}
