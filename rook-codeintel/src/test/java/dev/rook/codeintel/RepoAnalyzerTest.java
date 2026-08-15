package dev.rook.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.codeintel.project.LocalMavenRepository;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.json.Json;
import dev.rook.testkit.TestJars;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoAnalyzerTest {

    @TempDir static Path shared;

    static Path repo;
    static RepoAnalyzer.Options options;

    private static void write(String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeAll
    static void setUp() throws IOException {
        Path m2 = Files.createDirectories(shared.resolve("m2"));
        Path built =
                TestJars.build(
                        shared,
                        "acme-sdk",
                        Map.of(
                                "com.acme.pay.PaymentClient",
                                "package com.acme.pay; public class PaymentClient { public String charge(long amount) { return \"\"; } public void close() {} }"));
        Path installed = m2.resolve("com/acme/acme-sdk/1.0.0/acme-sdk-1.0.0.jar");
        Files.createDirectories(installed.getParent());
        Files.copy(built, installed);
        repo = Files.createDirectories(shared.resolve("repo"));
        write(
                "pom.xml",
                "<project><groupId>com.shop</groupId><artifactId>shop</artifactId><version>1</version><dependencies><dependency><groupId>com.acme</groupId><artifactId>acme-sdk</artifactId><version>1.0.0</version></dependency></dependencies></project>");
        write(".rook.yml", "version: 1\npaths:\n  exclude: [legacy]\n");
        write(
                "src/main/java/com/shop/Service.java",
                "package com.shop; import com.acme.pay.PaymentClient; class Service { PaymentClient client; void go() { client.charge(5); client.close(); } }");
        write(
                "legacy/src/main/java/com/legacy/Old.java",
                "package com.legacy; import com.acme.pay.PaymentClient; class Old { PaymentClient c; void go() { c.charge(1); } }");
        write("src/main/java/com/shop/Broken.java", "package com.shop; class Broken { void x( }");
        options = RepoAnalyzer.Options.defaults().withMavenRepository(new LocalMavenRepository(m2));
    }

    private static ChangeEvent rename(String method) {
        return ChangeEvent.of(
                "acme-sdk",
                "1.0.0",
                "2.0.0",
                ChangeKind.METHOD_RENAMED,
                SymbolRef.javaMethod(
                        "com.acme.pay.PaymentClient",
                        method,
                        method.equals("charge") ? List.of("long") : List.of()),
                Map.of(),
                method + " renamed");
    }

    @Test
    void impactedRepositoriesNeedMigration() {
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(repo, List.of(rename("charge")), options);
        assertThat(analysis.assessment().decision())
                .isEqualTo(RepoAnalyzer.Decision.NEEDS_MIGRATION);
        assertThat(analysis.assessment().actionableLocations()).isPositive();
        assertThat(analysis.report().impacts()).hasSize(1);
        assertThat(analysis.report().impacts().get(0).locations())
                .allMatch(l -> l.confidence() == Confidence.RESOLVED);
        assertThat(analysis.assessment().summary()).contains("1 of 1 changes touch the repository");
    }

    @Test
    void configuredExclusionsKeepLegacyCodeOutOfTheReport() {
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(repo, List.of(rename("charge")), options);
        assertThat(analysis.report().affectedFiles())
                .containsExactly("src/main/java/com/shop/Service.java");
    }

    @Test
    void unusedChangesMeanNoImpact() {
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(repo, List.of(rename("neverUsed")), options);
        assertThat(analysis.assessment().decision()).isEqualTo(RepoAnalyzer.Decision.NO_IMPACT);
        assertThat(analysis.assessment().summary()).contains("no usage found");
        assertThat(analysis.report().unmatched()).hasSize(1);
    }

    @Test
    void emptyEventListsMeanNoImpact() {
        assertThat(RepoAnalyzer.analyze(repo, List.of(), options).assessment().decision())
                .isEqualTo(RepoAnalyzer.Decision.NO_IMPACT);
    }

    @Test
    void weakMatchesOnlyAreReviewedNotEdited() {
        RepoAnalyzer.Options strict =
                options.withoutSymbolResolution().withMinimumConfidence(Confidence.RESOLVED);
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(repo, List.of(rename("charge")), strict);
        assertThat(analysis.assessment().decision()).isEqualTo(RepoAnalyzer.Decision.REVIEW_ONLY);
        assertThat(analysis.assessment().actionableLocations()).isZero();
        assertThat(analysis.assessment().summary()).contains("none strong enough");
    }

    @Test
    void parseProblemsSurfaceAsWarnings() {
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(repo, List.of(rename("charge")), options);
        assertThat(analysis.warnings()).anyMatch(w -> w.contains("Broken.java"));
    }

    @Test
    void timingsAreRecorded() {
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(repo, List.of(rename("charge")), options);
        assertThat(analysis.indexMillis()).isNotNegative();
        assertThat(analysis.analyzeMillis()).isNotNegative();
    }

    @Test
    void reportsSurviveAJsonRoundTrip() {
        ImpactReport report =
                RepoAnalyzer.analyze(repo, List.of(rename("charge"), rename("close")), options)
                        .report();
        ImpactReport copy = Json.read(Json.write(report), ImpactReport.class);
        assertThat(copy).isEqualTo(report);
    }
}
