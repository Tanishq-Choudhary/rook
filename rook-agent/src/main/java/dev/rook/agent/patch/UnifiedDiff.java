package dev.rook.agent.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UnifiedDiff {

    private static final int CONTEXT = 3;
    private static final String NO_NEWLINE = "\\ No newline at end of file";
    private static final Pattern HUNK =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private UnifiedDiff() {}

    public static String generate(String path, String before, String after) {
        if (before != null && before.equals(after)) {
            return "";
        }
        TextLines old = TextLines.parse(before == null ? "" : before);
        TextLines next = TextLines.parse(after == null ? "" : after);
        List<LineDiff.Op> ops = LineDiff.diff(old.keys(), next.keys());
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(before == null ? FileDiff.NONE : "a/" + path).append('\n');
        out.append("+++ ").append(after == null ? FileDiff.NONE : "b/" + path).append('\n');
        int i = 0;
        while (i < ops.size()) {
            while (i < ops.size() && ops.get(i).kind() == LineDiff.Kind.EQUAL) {
                i++;
            }
            if (i >= ops.size()) {
                break;
            }
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            int quiet = 0;
            int j = i;
            while (j < ops.size()) {
                if (ops.get(j).kind() == LineDiff.Kind.EQUAL) {
                    quiet++;
                    if (quiet > 2 * CONTEXT) {
                        break;
                    }
                } else {
                    quiet = 0;
                    end = j;
                }
                j++;
            }
            int stop = Math.min(ops.size(), end + 1 + CONTEXT);
            writeHunk(out, ops.subList(start, stop), old, next);
            i = stop;
        }
        return out.toString();
    }

    private static void writeHunk(
            StringBuilder out, List<LineDiff.Op> ops, TextLines old, TextLines next) {
        int oldCount = 0;
        int newCount = 0;
        int oldStart = -1;
        int newStart = -1;
        StringBuilder body = new StringBuilder();
        for (LineDiff.Op op : ops) {
            switch (op.kind()) {
                case EQUAL -> {
                    oldStart = oldStart < 0 ? op.oldIndex() : oldStart;
                    newStart = newStart < 0 ? op.newIndex() : newStart;
                    oldCount++;
                    newCount++;
                    body.append(' ').append(old.lines().get(op.oldIndex())).append('\n');
                    marker(body, op.oldIndex() == old.lines().size() - 1 && !old.endsWithNewline());
                }
                case DELETE -> {
                    oldStart = oldStart < 0 ? op.oldIndex() : oldStart;
                    oldCount++;
                    body.append('-').append(old.lines().get(op.oldIndex())).append('\n');
                    marker(body, op.oldIndex() == old.lines().size() - 1 && !old.endsWithNewline());
                }
                case INSERT -> {
                    newStart = newStart < 0 ? op.newIndex() : newStart;
                    newCount++;
                    body.append('+').append(next.lines().get(op.newIndex())).append('\n');
                    marker(
                            body,
                            op.newIndex() == next.lines().size() - 1 && !next.endsWithNewline());
                }
            }
        }
        if (oldStart < 0) {
            oldStart = firstOldIndex(ops);
        }
        if (newStart < 0) {
            newStart = firstNewIndex(ops);
        }
        out.append("@@ -")
                .append(range(oldStart, oldCount))
                .append(" +")
                .append(range(newStart, newCount))
                .append(" @@\n")
                .append(body);
    }

    private static int firstOldIndex(List<LineDiff.Op> ops) {
        for (LineDiff.Op op : ops) {
            if (op.oldIndex() >= 0) {
                return op.oldIndex();
            }
        }
        return 0;
    }

    private static int firstNewIndex(List<LineDiff.Op> ops) {
        for (LineDiff.Op op : ops) {
            if (op.newIndex() >= 0) {
                return op.newIndex();
            }
        }
        return 0;
    }

    private static void marker(StringBuilder body, boolean missingNewline) {
        if (missingNewline) {
            body.append(NO_NEWLINE).append('\n');
        }
    }

    private static String range(int zeroBasedStart, int count) {
        int start = count == 0 ? zeroBasedStart : zeroBasedStart + 1;
        return count == 1 ? Integer.toString(start) : start + "," + count;
    }

    public static List<FileDiff> parse(String diff) {
        List<String> lines = new ArrayList<>(List.of(diff.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        List<FileDiff> files = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (!line.startsWith("--- ")) {
                if (line.startsWith("diff ") || line.startsWith("index ") || line.isBlank()) {
                    i++;
                    continue;
                }
                throw malformed("unexpected line before a file header: " + clip(line));
            }
            if (i + 1 >= lines.size() || !lines.get(i + 1).startsWith("+++ ")) {
                throw malformed("missing +++ header");
            }
            String oldPath = headerPath(line.substring(4));
            String newPath = headerPath(lines.get(i + 1).substring(4));
            i += 2;
            List<FileDiff.Hunk> hunks = new ArrayList<>();
            while (i < lines.size() && lines.get(i).startsWith("@@")) {
                int[] next = new int[1];
                hunks.add(parseHunk(lines, i, next));
                i = next[0];
            }
            if (hunks.isEmpty()) {
                throw malformed("file header without hunks");
            }
            files.add(new FileDiff(oldPath, newPath, hunks));
        }
        if (files.isEmpty()) {
            throw malformed("no file diffs found");
        }
        return files;
    }

    private static FileDiff.Hunk parseHunk(List<String> lines, int at, int[] next) {
        Matcher header = HUNK.matcher(lines.get(at));
        if (!header.matches()) {
            throw malformed("bad hunk header: " + clip(lines.get(at)));
        }
        int oldStart = Integer.parseInt(header.group(1));
        int oldCount = header.group(2) == null ? 1 : Integer.parseInt(header.group(2));
        int newStart = Integer.parseInt(header.group(3));
        int newCount = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));
        List<String> body = new ArrayList<>();
        boolean oldNo = false;
        boolean newNo = false;
        int seenOld = 0;
        int seenNew = 0;
        int i = at + 1;
        while (i < lines.size()
                && (seenOld < oldCount || seenNew < newCount || isMarker(lines, i))) {
            String line = lines.get(i);
            if (line.equals(NO_NEWLINE)) {
                if (body.isEmpty()) {
                    throw malformed("newline marker without a line");
                }
                char last = body.get(body.size() - 1).charAt(0);
                oldNo |= last == '-' || last == ' ';
                newNo |= last == '+' || last == ' ';
                i++;
                continue;
            }
            if (line.isEmpty()) {
                line = " ";
            }
            char tag = line.charAt(0);
            if (tag == ' ') {
                seenOld++;
                seenNew++;
            } else if (tag == '-') {
                seenOld++;
            } else if (tag == '+') {
                seenNew++;
            } else {
                throw malformed("bad hunk line: " + clip(line));
            }
            body.add(line);
            i++;
        }
        if (seenOld != oldCount || seenNew != newCount) {
            throw malformed("hunk counts do not match its lines");
        }
        next[0] = i;
        return new FileDiff.Hunk(oldStart, oldCount, newStart, newCount, body, oldNo, newNo);
    }

    private static boolean isMarker(List<String> lines, int i) {
        return lines.get(i).equals(NO_NEWLINE);
    }

    private static String headerPath(String raw) {
        String path = raw;
        int tab = path.indexOf('\t');
        if (tab >= 0) {
            path = path.substring(0, tab);
        }
        path = path.trim();
        if (path.equals(FileDiff.NONE)) {
            return path;
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }

    private static PatchRejected malformed(String message) {
        return new PatchRejected(PatchRejected.Reason.MALFORMED, message);
    }

    private static String clip(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60);
    }

    public static String apply(String original, String diff) {
        List<FileDiff> files = parse(diff);
        if (files.size() != 1) {
            throw malformed("expected a diff for exactly one file");
        }
        return files.get(0).applyTo(original);
    }
}
