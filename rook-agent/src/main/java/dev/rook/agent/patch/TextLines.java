package dev.rook.agent.patch;

import java.util.ArrayList;
import java.util.List;

record TextLines(List<String> lines, boolean endsWithNewline) {

    static TextLines parse(String text) {
        if (text.isEmpty()) {
            return new TextLines(List.of(), true);
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        boolean ends = text.endsWith("\n");
        if (ends) {
            lines.remove(lines.size() - 1);
        }
        return new TextLines(lines, ends);
    }

    String render() {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + (endsWithNewline ? "\n" : "");
    }

    List<String> keys() {
        List<String> keys = new ArrayList<>(lines);
        if (!endsWithNewline && !keys.isEmpty()) {
            int last = keys.size() - 1;
            keys.set(last, keys.get(last) + "\u0000no-newline");
        }
        return keys;
    }
}
