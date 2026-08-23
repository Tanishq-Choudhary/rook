package dev.rook.agent.patch;

import java.util.ArrayList;
import java.util.List;

public record FileDiff(String oldPath, String newPath, List<Hunk> hunks) {

    public static final String NONE = "/dev/null";

    public record Hunk(
            int oldStart,
            int oldCount,
            int newStart,
            int newCount,
            List<String> lines,
            boolean oldNoNewline,
            boolean newNoNewline) {}

    public FileDiff {
        hunks = List.copyOf(hunks);
    }

    public boolean isNew() {
        return oldPath.equals(NONE);
    }

    public boolean isDeletion() {
        return newPath.equals(NONE);
    }

    public String path() {
        return isNew() ? newPath : oldPath;
    }

    public int changedLines() {
        int changed = 0;
        for (Hunk hunk : hunks) {
            for (String line : hunk.lines()) {
                if (line.startsWith("+") || line.startsWith("-")) {
                    changed++;
                }
            }
        }
        return changed;
    }

    public String applyTo(String original) {
        TextLines source = TextLines.parse(original == null ? "" : original);
        if (isNew() && original != null && !original.isEmpty()) {
            throw new PatchRejected(
                    PatchRejected.Reason.CONTEXT_MISMATCH, "the patch creates a file that exists");
        }
        List<String> lines = new ArrayList<>(source.lines());
        boolean endsWithNewline = source.endsWithNewline();
        int delta = 0;
        int minNext = 0;
        for (Hunk hunk : hunks) {
            List<String> expected = new ArrayList<>();
            List<String> replacement = new ArrayList<>();
            for (String line : hunk.lines()) {
                String body = line.substring(1);
                if (line.startsWith(" ") || line.startsWith("-")) {
                    expected.add(body);
                }
                if (line.startsWith(" ") || line.startsWith("+")) {
                    replacement.add(body);
                }
            }
            int stated =
                    Math.max(0, hunk.oldCount() == 0 ? hunk.oldStart() : hunk.oldStart() - 1)
                            + delta;
            int at = locate(lines, expected, stated, minNext, hunk, endsWithNewline);
            for (int i = 0; i < expected.size(); i++) {
                lines.remove(at);
            }
            lines.addAll(at, replacement);
            delta += replacement.size() - expected.size();
            minNext = at + replacement.size();
            if (at + replacement.size() == lines.size() && !lines.isEmpty()) {
                endsWithNewline = !hunk.newNoNewline();
            } else if (lines.isEmpty()) {
                endsWithNewline = true;
            }
        }
        if (isDeletion()) {
            if (!lines.isEmpty()) {
                throw new PatchRejected(
                        PatchRejected.Reason.CONTEXT_MISMATCH,
                        "the patch deletes a file but leaves content behind");
            }
            return null;
        }
        return new TextLines(lines, endsWithNewline).render();
    }

    private static int locate(
            List<String> lines,
            List<String> expected,
            int stated,
            int minStart,
            Hunk hunk,
            boolean endsWithNewline) {
        if (matches(lines, expected, stated, hunk, endsWithNewline) && stated >= minStart) {
            return stated;
        }
        int found = -1;
        for (int i = minStart; i + expected.size() <= lines.size(); i++) {
            if (matches(lines, expected, i, hunk, endsWithNewline)) {
                if (found >= 0) {
                    throw new PatchRejected(
                            PatchRejected.Reason.AMBIGUOUS,
                            "hunk at line " + hunk.oldStart() + " matches several places");
                }
                found = i;
            }
        }
        if (found < 0) {
            throw new PatchRejected(
                    PatchRejected.Reason.CONTEXT_MISMATCH,
                    "hunk at line " + hunk.oldStart() + " does not match the file");
        }
        return found;
    }

    private static boolean matches(
            List<String> lines, List<String> expected, int at, Hunk hunk, boolean endsWithNewline) {
        if (at < 0 || at + expected.size() > lines.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!lines.get(at + i).equals(expected.get(i))) {
                return false;
            }
        }
        boolean touchesEnd = at + expected.size() == lines.size() && !expected.isEmpty();
        return !touchesEnd || hunk.oldNoNewline() == !endsWithNewline;
    }
}
