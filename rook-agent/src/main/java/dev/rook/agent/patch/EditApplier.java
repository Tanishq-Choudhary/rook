package dev.rook.agent.patch;

import java.util.ArrayList;
import java.util.List;

public final class EditApplier {

    public record Applied(String content, int line) {}

    private EditApplier() {}

    public static Applied apply(String content, String search, String replace) {
        if (search.isEmpty()) {
            throw new PatchRejected(
                    PatchRejected.Reason.EMPTY_SEARCH, "the text to replace must not be empty");
        }
        if (search.equals(replace)) {
            throw new PatchRejected(
                    PatchRejected.Reason.NO_CHANGE, "the replacement is identical to the original");
        }
        int first = content.indexOf(search);
        if (first >= 0) {
            if (content.indexOf(search, first + 1) >= 0) {
                throw new PatchRejected(
                        PatchRejected.Reason.AMBIGUOUS,
                        "the text to replace occurs more than once, include more surrounding lines");
            }
            return new Applied(
                    content.substring(0, first)
                            + replace
                            + content.substring(first + search.length()),
                    lineOf(content, first));
        }
        return applyTolerant(content, search, replace);
    }

    private static Applied applyTolerant(String content, String search, String replace) {
        boolean crlf = content.contains("\r\n");
        List<int[]> spans = lineSpans(content);
        List<String> lines = new ArrayList<>();
        for (int[] span : spans) {
            lines.add(stripTrailing(content.substring(span[0], span[1])));
        }
        boolean searchEndsWithNewline = search.endsWith("\n");
        List<String> wanted = new ArrayList<>();
        for (String raw : search.split("\n", -1)) {
            wanted.add(stripTrailing(raw));
        }
        if (searchEndsWithNewline) {
            wanted.remove(wanted.size() - 1);
        }
        while (!wanted.isEmpty() && wanted.get(0).isEmpty()) {
            wanted.remove(0);
        }
        while (!wanted.isEmpty() && wanted.get(wanted.size() - 1).isEmpty()) {
            wanted.remove(wanted.size() - 1);
        }
        if (wanted.isEmpty()) {
            throw new PatchRejected(
                    PatchRejected.Reason.EMPTY_SEARCH, "the text to replace must not be blank");
        }
        int match = -1;
        for (int i = 0; i + wanted.size() <= lines.size(); i++) {
            if (lines.subList(i, i + wanted.size()).equals(wanted)) {
                if (match >= 0) {
                    throw new PatchRejected(
                            PatchRejected.Reason.AMBIGUOUS,
                            "the text to replace occurs more than once, include more surrounding lines");
                }
                match = i;
            }
        }
        if (match < 0) {
            throw new PatchRejected(
                    PatchRejected.Reason.NOT_FOUND,
                    "the text to replace was not found in the file");
        }
        int start = spans.get(match)[0];
        int end = spans.get(match + wanted.size() - 1)[1];
        String body = replace;
        if (searchEndsWithNewline && body.endsWith("\n")) {
            body = body.substring(0, body.length() - 1);
        }
        body = body.replace("\r\n", "\n");
        if (crlf) {
            body = body.replace("\n", "\r\n");
        }
        return new Applied(content.substring(0, start) + body + content.substring(end), match + 1);
    }

    private static List<int[]> lineSpans(String content) {
        List<int[]> spans = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                int end = i > start && content.charAt(i - 1) == '\r' ? i - 1 : i;
                spans.add(new int[] {start, end});
                start = i + 1;
            }
        }
        if (start < content.length()) {
            spans.add(new int[] {start, content.length()});
        }
        return spans;
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0
                && (line.charAt(end - 1) == ' '
                        || line.charAt(end - 1) == '\t'
                        || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
