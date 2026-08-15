package dev.rook.codeintel;

import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.ImpactAnalyzer;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.JavaIndexer;
import dev.rook.codeintel.java.SymbolResolver;
import dev.rook.codeintel.project.Dependency;
import dev.rook.codeintel.project.LocalMavenRepository;
import dev.rook.codeintel.project.RepoProfile;
import dev.rook.core.change.ChangeEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RepoAnalyzer {

    public enum Decision {
        NO_IMPACT,
        REVIEW_ONLY,
        NEEDS_MIGRATION
    }

    public record Options(
            LocalMavenRepository mavenRepository,
            List<Path> extraJars,
            Confidence minimumConfidence,
            boolean resolveSymbols) {

        public Options {
            extraJars = extraJars == null ? List.of() : List.copyOf(extraJars);
            minimumConfidence =
                    minimumConfidence == null ? Confidence.HEURISTIC : minimumConfidence;
        }

        public static Options defaults() {
            return new Options(
                    LocalMavenRepository.userDefault(), List.of(), Confidence.HEURISTIC, true);
        }

        public Options withMavenRepository(LocalMavenRepository repository) {
            return new Options(repository, extraJars, minimumConfidence, resolveSymbols);
        }

        public Options withMinimumConfidence(Confidence confidence) {
            return new Options(mavenRepository, extraJars, confidence, resolveSymbols);
        }

        public Options withoutSymbolResolution() {
            return new Options(mavenRepository, extraJars, minimumConfidence, false);
        }
    }

    public record Assessment(Decision decision, int actionableLocations, String summary) {}

    public record Analysis(
            RepoProfile profile,
            ImpactReport report,
            Assessment assessment,
            long indexMillis,
            long analyzeMillis,
            List<String> warnings) {}

    private RepoAnalyzer() {}

    public static Analysis analyze(Path root, List<ChangeEvent> events, Options options) {
        long started = System.nanoTime();
        RepoProfile profile = RepoProfile.detect(root);
        List<String> roots = new ArrayList<>(profile.mainRoots());
        roots.addAll(profile.testRoots());
        CodeIndex index = JavaIndexer.index(root, roots);
        long indexed = System.nanoTime();

        SymbolResolver resolver = null;
        if (options.resolveSymbols()) {
            List<Dependency> dependencies = profile.scan().dependencies();
            List<Path> jars = new ArrayList<>(options.mavenRepository().jars(dependencies));
            jars.addAll(options.extraJars());
            resolver = SymbolResolver.create(index, roots, jars);
        }
        ImpactReport report = ImpactAnalyzer.create(index, resolver).analyze(events);
        long analyzed = System.nanoTime();

        List<String> warnings = new ArrayList<>(profile.scan().warnings());
        warnings.addAll(index.problems());
        return new Analysis(
                profile,
                report,
                assess(report, options.minimumConfidence()),
                (indexed - started) / 1_000_000,
                (analyzed - indexed) / 1_000_000,
                List.copyOf(warnings));
    }

    static Assessment assess(ImpactReport report, Confidence minimum) {
        int actionable = 0;
        int resolved = 0;
        int heuristic = 0;
        int textual = 0;
        for (var impact : report.impacts()) {
            for (ImpactLocation location : impact.locations()) {
                switch (location.confidence()) {
                    case RESOLVED -> resolved++;
                    case HEURISTIC -> heuristic++;
                    case TEXTUAL -> textual++;
                }
                if (location.confidence().compareTo(minimum) >= 0) {
                    actionable++;
                }
            }
        }
        int analyzed = report.stats().eventsAnalyzed();
        if (analyzed == 0 || report.impacts().isEmpty()) {
            return new Assessment(
                    Decision.NO_IMPACT,
                    0,
                    String.format(
                            Locale.ROOT,
                            "%d changes analyzed, no usage found in the repository",
                            analyzed));
        }
        String detail =
                String.format(
                        Locale.ROOT,
                        "%d of %d changes touch the repository: %d locations in %d files (%d resolved, %d heuristic, %d textual)",
                        report.impacts().size(),
                        analyzed,
                        report.stats().locations(),
                        report.stats().affectedFiles(),
                        resolved,
                        heuristic,
                        textual);
        if (actionable == 0) {
            return new Assessment(
                    Decision.REVIEW_ONLY, 0, detail + ", none strong enough to edit automatically");
        }
        return new Assessment(Decision.NEEDS_MIGRATION, actionable, detail);
    }
}
