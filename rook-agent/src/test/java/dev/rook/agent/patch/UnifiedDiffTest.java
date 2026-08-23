package dev.rook.agent.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffTest {

    private static String lines(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            out.append("line ").append(i).append('\n');
        }
        return out.toString();
    }

    private static PatchRejected.Reason rejection(String original, String diff) {
        try {
            UnifiedDiff.apply(original, diff);
        } catch (PatchRejected e) {
            return e.reason();
        }
        throw new AssertionError("expected a rejection");
    }

    @Test
    void identicalTextsProduceNoDiff() {
        assertThat(UnifiedDiff.generate("a.txt", "x\n", "x\n")).isEmpty();
    }

    @Test
    void aSingleLineChangeHasContextAndRoundTrips() {
        String before = lines(10);
        String after = before.replace("line 5\n", "line five\n");
        String diff = UnifiedDiff.generate("src/a.txt", before, after);
        assertThat(diff)
                .startsWith("--- a/src/a.txt\n+++ b/src/a.txt\n@@ -2,7 +2,7 @@\n")
                .contains("-line 5\n+line five\n");
        assertThat(UnifiedDiff.apply(before, diff)).isEqualTo(after);
    }

    @Test
    void distantChangesBecomeSeparateHunks() {
        String before = lines(40);
        String after = before.replace("line 3\n", "three\n").replace("line 38\n", "thirty eight\n");
        String diff = UnifiedDiff.generate("a.txt", before, after);
        assertThat(diff.lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(2);
        assertThat(UnifiedDiff.apply(before, diff)).isEqualTo(after);
    }

    @Test
    void nearbyChangesShareOneHunk() {
        String before = lines(20);
        String after = before.replace("line 8\n", "eight\n").replace("line 12\n", "twelve\n");
        String diff = UnifiedDiff.generate("a.txt", before, after);
        assertThat(diff.lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(1);
        assertThat(UnifiedDiff.apply(before, diff)).isEqualTo(after);
    }

    @Test
    void insertionsAndDeletionsRoundTrip() {
        String before = lines(12);
        String after =
                before.replace("line 4\n", "").replace("line 9\n", "line 9\nextra a\nextra b\n");
        assertThat(UnifiedDiff.apply(before, UnifiedDiff.generate("a.txt", before, after)))
                .isEqualTo(after);
    }

    @Test
    void newFilesAndDeletedFilesRoundTrip() {
        String created = UnifiedDiff.generate("n.txt", null, "a\nb\n");
        assertThat(created).startsWith("--- /dev/null\n+++ b/n.txt\n@@ -0,0 +1,2 @@\n");
        FileDiff parsed = UnifiedDiff.parse(created).get(0);
        assertThat(parsed.isNew()).isTrue();
        assertThat(parsed.path()).isEqualTo("n.txt");
        assertThat(parsed.applyTo(null)).isEqualTo("a\nb\n");
        String deleted = UnifiedDiff.generate("n.txt", "a\nb\n", null);
        FileDiff gone = UnifiedDiff.parse(deleted).get(0);
        assertThat(gone.isDeletion()).isTrue();
        assertThat(gone.applyTo("a\nb\n")).isNull();
    }

    @Test
    void creatingAFileThatExistsIsRejected() {
        FileDiff created = UnifiedDiff.parse(UnifiedDiff.generate("n.txt", null, "a\n")).get(0);
        assertThatThrownBy(() -> created.applyTo("already here\n"))
                .isInstanceOfSatisfying(
                        PatchRejected.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(PatchRejected.Reason.CONTEXT_MISMATCH));
    }

    @Test
    void missingTrailingNewlinesAreTrackedOnBothSides() {
        for (String[] pair :
                new String[][] {
                    {"a\nb", "a\nb\n"},
                    {"a\nb\n", "a\nb"},
                    {"a\nb", "a\nc"},
                    {"a", "a\nb"},
                    {"", "x"},
                    {"x", ""}
                }) {
            String diff = UnifiedDiff.generate("f", pair[0], pair[1]);
            assertThat(UnifiedDiff.apply(pair[0], diff))
                    .as(pair[0] + " -> " + pair[1])
                    .isEqualTo(pair[1]);
        }
        assertThat(UnifiedDiff.generate("f", "a\nb", "a\nb\n"))
                .contains("\\ No newline at end of file");
    }

    @Test
    void windowsLineEndingsSurviveARoundTrip() {
        String before = "one\r\ntwo\r\nthree\r\n";
        String after = "one\r\n2\r\nthree\r\n";
        assertThat(UnifiedDiff.apply(before, UnifiedDiff.generate("f", before, after)))
                .isEqualTo(after);
    }

    @Test
    void aHunkThatMovedIsRelocatedWhenTheMatchIsUnique() {
        String before = lines(30);
        String after = before.replace("line 20\n", "twenty\n");
        String diff = UnifiedDiff.generate("a.txt", before, after);
        String shifted = "inserted at the top\nanother\n" + before;
        assertThat(UnifiedDiff.apply(shifted, diff))
                .isEqualTo("inserted at the top\nanother\n" + after);
    }

    @Test
    void aHunkWhoseContextChangedIsRejected() {
        String before = lines(10);
        String diff = UnifiedDiff.generate("a.txt", before, before.replace("line 5\n", "five\n"));
        String drifted = before.replace("line 5\n", "something else\n");
        assertThat(rejection(drifted, diff)).isEqualTo(PatchRejected.Reason.CONTEXT_MISMATCH);
    }

    @Test
    void aHunkThatFitsSeveralPlacesIsRejectedAsAmbiguous() {
        String block = "a\nb\nc\nd\ne\nf\ng\n";
        String before = block + "x\n" + block;
        String diff = "--- a/f\n+++ b/f\n@@ -90,3 +90,3 @@\n a\n-b\n+B\n c\n";
        assertThat(rejection(before, diff)).isEqualTo(PatchRejected.Reason.AMBIGUOUS);
    }

    @Test
    void malformedDiffsAreRejectedWithAReason() {
        assertThat(rejection("a\n", "garbage")).isEqualTo(PatchRejected.Reason.MALFORMED);
        assertThat(rejection("a\n", "--- a/f\n+++ b/f\n"))
                .isEqualTo(PatchRejected.Reason.MALFORMED);
        assertThat(rejection("a\n", "--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n a\n"))
                .isEqualTo(PatchRejected.Reason.MALFORMED);
        assertThat(rejection("a\n", "--- a/f\n+++ b/f\n@@ nonsense @@\n a\n"))
                .isEqualTo(PatchRejected.Reason.MALFORMED);
        assertThat(rejection("a\n", "--- a/f\n")).isEqualTo(PatchRejected.Reason.MALFORMED);
        assertThat(rejection("a\n", "--- a/f\n+++ b/f\n@@ -1 +1 @@\n?a\n"))
                .isEqualTo(PatchRejected.Reason.MALFORMED);
    }

    @Test
    void multipleFilesAreParsedAndSingleFileApplyRefusesThem() {
        String diff =
                UnifiedDiff.generate("a.txt", "1\n", "2\n")
                        + UnifiedDiff.generate("b.txt", "x\n", "y\n");
        List<FileDiff> files = UnifiedDiff.parse(diff);
        assertThat(files).extracting(FileDiff::path).containsExactly("a.txt", "b.txt");
        assertThat(files.get(0).changedLines()).isEqualTo(2);
        assertThat(rejection("1\n", diff)).isEqualTo(PatchRejected.Reason.MALFORMED);
    }

    @Test
    void gitStyleHeadersAreAccepted() {
        String diff =
                "diff --git a/f b/f\nindex 1..2 100644\n--- a/f\t2026-01-01\n+++ b/f\n@@ -1 +1 @@\n-a\n+b\n";
        assertThat(UnifiedDiff.apply("a\n", diff)).isEqualTo("b\n");
    }

    @Test
    void hugeFilesAreRefusedInsteadOfExhaustingMemory() {
        String big = "x\n".repeat(LineDiff.MAX_LINES + 1);
        assertThatThrownBy(() -> UnifiedDiff.generate("f", big, big + "y\n"))
                .isInstanceOfSatisfying(
                        PatchRejected.class,
                        e -> assertThat(e.reason()).isEqualTo(PatchRejected.Reason.TOO_LARGE));
    }
}
