package dev.rook.github.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrBodyTest {

    private static PrReport report(List<PrReport.CallSite> sites, List<String> risks) {
        return new PrReport(
                "acme/shop",
                "acme-sdk",
                "1.4.0",
                "2.0.0",
                List.of("PayClient.charge is now createCharge", "PayClient moved to a new package"),
                sites,
                new PrReport.Diff(3, 14, 9),
                new PrReport.Verification(
                        42,
                        0,
                        42,
                        0,
                        List.of("ServiceTest.retries"),
                        List.of("build", "tests", "limits")),
                risks,
                "codemod-only",
                "https://rook.example.test/jobs/1");
    }

    private static List<PrReport.CallSite> sites(int count) {
        List<PrReport.CallSite> sites = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sites.add(new PrReport.CallSite("src/main/java/A" + (i % 3) + ".java", i + 1, "call"));
        }
        return sites;
    }

    @Test
    void theBodyExplainsTheChangeTheCallSitesAndTheVerification() {
        String body = PrBody.render(report(sites(4), List.of("The retry wrapper was not touched")));
        assertThat(body)
                .startsWith("acme-sdk changed from 1.4.0 to 2.0.0")
                .contains("## What changed upstream")
                .contains("- PayClient.charge is now createCharge")
                .contains("## Where it touches this repository")
                .contains("4 places in 3 files.")
                .contains("- `src/main/java/A0.java:1` call")
                .contains(
                        "3 files changed, 14 lines added, 9 removed. Produced by the codemod-only strategy.")
                .contains("Before the change: 42 tests ran, 0 failed.")
                .contains("After the change: 42 tests ran, 0 failed.")
                .contains("- `ServiceTest.retries`")
                .contains("Checks passed: build, tests, limits.")
                .contains("## Risks and open items")
                .contains("- The retry wrapper was not touched")
                .endsWith("Job details: https://rook.example.test/jobs/1\n");
    }

    @Test
    void longListsAreCutWithACount() {
        String body = PrBody.render(report(sites(40), List.of()));
        assertThat(body).contains("- and 15 more");
        assertThat(body).contains("- None recorded.");
        assertThat(body.lines().filter(line -> line.startsWith("- `src/")).count()).isEqualTo(25);
    }

    @Test
    void noCallSitesAreSaidPlainly() {
        assertThat(PrBody.render(report(List.of(), List.of())))
                .contains("No call sites were found.");
    }

    @Test
    void oneFileIsNotPluralised() {
        PrReport single =
                new PrReport(
                        "acme/shop",
                        "acme-sdk",
                        "1",
                        "2",
                        List.of("x"),
                        List.of(new PrReport.CallSite("A.java", 3, null)),
                        new PrReport.Diff(1, 2, 1),
                        new PrReport.Verification(1, 0, 1, 0, List.of(), List.of()),
                        List.of(),
                        "agent",
                        null);
        String body = PrBody.render(single);
        assertThat(body).contains("1 places in 1 file.").contains("1 file changed");
        assertThat(body).doesNotContain("Job details");
        assertThat(body).doesNotContain("Checks passed").doesNotContain("inconsistently");
    }

    @Test
    void multiLineAndOversizedNotesStayOnOneLine() {
        String note = "first line\nsecond line " + "x".repeat(400);
        String body = PrBody.render(report(sites(1), List.of(note)));
        String risk =
                body.lines()
                        .filter(line -> line.startsWith("- first line"))
                        .findFirst()
                        .orElseThrow();
        assertThat(risk).startsWith("- first line second line").endsWith("...");
        assertThat(risk.length()).isLessThan(310);
    }

    @Test
    void theWholeDescriptionNeverExceedsTheGithubLimit() {
        List<String> risks = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            risks.add("r".repeat(299));
        }
        String body = PrBody.render(report(sites(10), risks));
        assertThat(body.length()).isLessThanOrEqualTo(PrBody.MAX_LENGTH);
    }

    @Test
    void titlesAreShortAndSayWhatChanged() {
        assertThat(PrBody.title(report(sites(1), List.of())))
                .isEqualTo("Update for acme-sdk 1.4.0 to 2.0.0");
        PrReport long_ =
                new PrReport(
                        "a/b",
                        "s".repeat(200),
                        "1",
                        "2",
                        List.of(),
                        List.of(),
                        new PrReport.Diff(0, 0, 0),
                        new PrReport.Verification(0, 0, 0, 0, List.of(), List.of()),
                        List.of(),
                        "x",
                        null);
        assertThat(PrBody.title(long_)).hasSize(120).endsWith("...");
    }

    @Test
    void textNeverContainsDashCharactersThatReadAsGenerated() {
        String body = PrBody.render(report(sites(3), List.of("one")));
        assertThat(body)
                .doesNotContain(String.valueOf((char) 0x2014))
                .doesNotContain(String.valueOf((char) 0x2013));
    }
}
