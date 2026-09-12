package dev.rook.verify.gate;

import dev.rook.agent.patch.FileDiff;
import dev.rook.agent.patch.UnifiedDiff;
import dev.rook.agent.workspace.WorkspacePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

public record DiffFacts(
        List<FileDiff> files,
        List<String> paths,
        int changedLines,
        List<String> buildFiles,
        List<String> protectedFiles,
        List<String> addedDependencyLines,
        List<String> addedLines,
        int removedAssertions,
        int addedAssertions,
        List<String> deletedTests) {

    private static final Pattern DEPENDENCY_LINE =
            Pattern.compile(
                    "<dependency>|^\\s*(implementation|api|compile|runtimeOnly|testImplementation)\\b");
    private static final Pattern ASSERTION =
            Pattern.compile(
                    "\\bassert[A-Z]?\\w*\\s*\\(|\\bassertThat\\b|\\bverify\\s*\\(|@Test\\b|\\bfail\\s*\\(");

    public DiffFacts {
        files = List.copyOf(files);
        paths = List.copyOf(paths);
        buildFiles = List.copyOf(buildFiles);
        protectedFiles = List.copyOf(protectedFiles);
        addedDependencyLines = List.copyOf(addedDependencyLines);
        addedLines = List.copyOf(addedLines);
        deletedTests = List.copyOf(deletedTests);
    }

    public static DiffFacts parse(String diff) {
        if (diff == null || diff.isEmpty()) {
            return new DiffFacts(
                    List.of(), List.of(), 0, List.of(), List.of(), List.of(), List.of(), 0, 0,
                    List.of());
        }
        List<FileDiff> files = UnifiedDiff.parse(diff);
        TreeSet<String> paths = new TreeSet<>();
        TreeSet<String> build = new TreeSet<>();
        TreeSet<String> protectedFiles = new TreeSet<>();
        TreeSet<String> deletedTests = new TreeSet<>();
        List<String> dependencies = new ArrayList<>();
        List<String> added = new ArrayList<>();
        int changed = 0;
        int removedAsserts = 0;
        int addedAsserts = 0;
        for (FileDiff file : files) {
            String path = file.path();
            paths.add(path);
            changed += file.changedLines();
            if (WorkspacePolicy.isBuildFile(path)) {
                build.add(path);
            }
            if (WorkspacePolicy.isProtectedFromEdits(path)) {
                protectedFiles.add(path);
            }
            boolean testFile = path.contains("src/test/");
            if (file.isDeletion() && testFile) {
                deletedTests.add(path);
            }
            for (FileDiff.Hunk hunk : file.hunks()) {
                for (String line : hunk.lines()) {
                    String body = line.substring(1);
                    if (line.startsWith("+")) {
                        added.add(body);
                        if (build.contains(path) && DEPENDENCY_LINE.matcher(body).find()) {
                            dependencies.add(path + ": " + body.strip());
                        }
                        if (testFile && ASSERTION.matcher(body).find()) {
                            addedAsserts++;
                        }
                    } else if (line.startsWith("-") && testFile && ASSERTION.matcher(body).find()) {
                        removedAsserts++;
                    }
                }
            }
        }
        return new DiffFacts(
                files,
                new ArrayList<>(paths),
                changed,
                new ArrayList<>(build),
                new ArrayList<>(protectedFiles),
                dependencies,
                added,
                removedAsserts,
                addedAsserts,
                new ArrayList<>(deletedTests));
    }
}
