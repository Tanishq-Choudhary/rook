package dev.rook.eval.analysis;

import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.project.LocalMavenRepository;
import dev.rook.eval.fixture.Fixture;
import java.nio.file.Path;
import java.util.List;

public record FixtureAnalysis(
        SdkChange change, RepoAnalyzer.Analysis analysis, ImpactScore score, long millis) {

    public static FixtureAnalysis run(Fixture fixture, Path work, Confidence minimum) {
        long started = System.nanoTime();
        SdkChange change = SdkChange.detect(fixture, work);
        RepoAnalyzer.Options options =
                new RepoAnalyzer.Options(
                        LocalMavenRepository.userDefault(),
                        List.of(change.beforeJar()),
                        minimum,
                        true);
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(fixture.repo(), change.events(), options);
        ImpactScore score = ImpactScorer.score(fixture.spec(), analysis.report(), minimum);
        return new FixtureAnalysis(
                change, analysis, score, (System.nanoTime() - started) / 1_000_000);
    }
}
