package dev.rook.agent.codemod;

import com.github.javaparser.Range;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

final class SourceEdits {

    private final String content;
    private final int[] lineStarts;
    private final TreeMap<Integer, int[]> spans = new TreeMap<>();
    private final TreeMap<Integer, String> replacements = new TreeMap<>();

    SourceEdits(String content) {
        this.content = content;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    boolean replace(Range range, String text) {
        if (range.begin.line < 1
                || range.end.line > lineStarts.length
                || range.begin.line > range.end.line) {
            return false;
        }
        int start = lineStarts[range.begin.line - 1] + range.begin.column - 1;
        int end = lineStarts[range.end.line - 1] + range.end.column;
        if (start < 0 || end > content.length() || start >= end) {
            return false;
        }
        if (content.substring(start, end).equals(text)) {
            return false;
        }
        Integer previous = spans.floorKey(start);
        if (previous != null && spans.get(previous)[1] > start) {
            return false;
        }
        Integer next = spans.ceilingKey(start);
        if (next != null && next < end) {
            return false;
        }
        spans.put(start, new int[] {start, end});
        replacements.put(start, text);
        return true;
    }

    boolean insertAfter(Range range, String text) {
        if (range.end.line < 1 || range.end.line > lineStarts.length) {
            return false;
        }
        int offset = lineStarts[range.end.line - 1] + range.end.column;
        if (offset > content.length() || spans.containsKey(offset)) {
            return false;
        }
        Integer previous = spans.floorKey(offset);
        if (previous != null && spans.get(previous)[1] > offset) {
            return false;
        }
        spans.put(offset, new int[] {offset, offset});
        replacements.put(offset, text);
        return true;
    }

    int count() {
        return spans.size();
    }

    String apply() {
        StringBuilder out = new StringBuilder(content);
        spans.descendingMap()
                .forEach((start, span) -> out.replace(span[0], span[1], replacements.get(start)));
        return out.toString();
    }
}
