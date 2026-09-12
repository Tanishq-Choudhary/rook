package dev.rook.verify.run;

import dev.rook.verify.build.BuildPlan;
import dev.rook.verify.build.JUnitReports;
import dev.rook.verify.build.RunSummary;
import dev.rook.verify.build.TestReport;
import dev.rook.verify.sandbox.Mount;
import dev.rook.verify.sandbox.NetworkMode;
import dev.rook.verify.sandbox.SandboxResult;
import dev.rook.verify.sandbox.SandboxRunner;
import dev.rook.verify.sandbox.SandboxSpec;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PhaseRunner {

    private static final Logger log = LoggerFactory.getLogger(PhaseRunner.class);

    private static final int OUTPUT_BYTES = 12 * 1024 * 1024;
    private static final int WORKSPACE_MB = 1024;

    private final SandboxRunner runner;

    public PhaseRunner(SandboxRunner runner) {
        this.runner = runner;
    }

    public RunSummary warm(BuildPlan plan, Path source, Path cache) {
        RunSummary summary = execute(plan, plan.warmCommand(), source, cache, true, "warm");
        if (summary.status() == RunSummary.Status.TESTS_RAN
                || summary.status() == RunSummary.Status.NO_TESTS) {
            markWarm(cache);
        }
        return summary;
    }

    public RunSummary test(BuildPlan plan, Path source, Path cache, String testFilter) {
        return execute(plan, plan.testCommandFor(testFilter), source, cache, false, "test");
    }

    public static boolean isWarm(Path cache) {
        return Files.isRegularFile(cache.resolve(".rook-warm"));
    }

    private static void markWarm(Path cache) {
        try {
            Files.writeString(cache.resolve(".rook-warm"), "warm\n");
        } catch (IOException e) {
            log.warn("could not mark the dependency cache as warm: {}", e.getMessage());
        }
    }

    private RunSummary execute(
            BuildPlan plan,
            List<String> command,
            Path source,
            Path cache,
            boolean network,
            String phase) {
        prepareCache(cache);
        SandboxSpec spec =
                new SandboxSpec(
                                plan.image(),
                                BuildPlan.WORK_PATH,
                                List.of("sh", "-c", ReportExport.script(command)),
                                plan.env(),
                                plan.timeout(),
                                2,
                                2048,
                                512,
                                network ? NetworkMode.BRIDGE : NetworkMode.NONE,
                                List.of(
                                        Mount.readOnly(source, BuildPlan.SOURCE_PATH),
                                        new Mount(cache, BuildPlan.CACHE_PATH, !network)),
                                Map.of("rook.phase", phase),
                                OUTPUT_BYTES)
                        .withTmpfs(
                                List.of(new SandboxSpec.Tmpfs(BuildPlan.WORK_PATH, WORKSPACE_MB)));
        SandboxResult result = runner.run(spec);
        return RunSummary.classify(result, report(result));
    }

    private static TestReport report(SandboxResult result) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("rook-reports-");
            ReportExport.extract(result.stdout(), directory);
            return JUnitReports.readAll(directory);
        } catch (IOException e) {
            log.warn("could not read the exported test reports: {}", e.getMessage());
            return TestReport.empty();
        } finally {
            delete(directory);
        }
    }

    private static void prepareCache(Path cache) {
        try {
            Files.createDirectories(cache);
            cache.toFile().setWritable(true, false);
            cache.toFile().setReadable(true, false);
            cache.toFile().setExecutable(true, false);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create the dependency cache at " + cache, e);
        }
    }

    private static void delete(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            Files.walkFileTree(
                    directory,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException e)
                                throws IOException {
                            Files.deleteIfExists(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.debug("could not remove {}: {}", directory, e.getMessage());
        }
    }
}
