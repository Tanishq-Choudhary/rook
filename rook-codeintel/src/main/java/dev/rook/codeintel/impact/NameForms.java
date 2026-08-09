package dev.rook.codeintel.impact;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class NameForms {

    private NameForms() {}

    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || c == ' ' || c == '.') {
                flush(words, current);
                continue;
            }
            boolean boundary =
                    current.length() > 0
                            && Character.isUpperCase(c)
                            && (Character.isLowerCase(current.charAt(current.length() - 1))
                                    || (i + 1 < name.length()
                                            && Character.isLowerCase(name.charAt(i + 1))));
            if (boundary) {
                flush(words, current);
            }
            current.append(c);
        }
        flush(words, current);
        return words;
    }

    static String camel(String name) {
        List<String> words = words(name);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            out.append(i == 0 ? words.get(i) : capitalize(words.get(i)));
        }
        return out.toString();
    }

    static String pascal(String name) {
        return capitalize(camel(name));
    }

    static String upperSnake(String name) {
        return String.join("_", words(name)).toUpperCase(Locale.ROOT);
    }

    static boolean sameWords(String left, String right) {
        return words(left).equals(words(right));
    }

    static List<String> accessorNames(String field) {
        String pascal = pascal(field);
        String camel = camel(field);
        return List.of("get" + pascal, "is" + pascal, "set" + pascal, camel, "with" + pascal);
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static void flush(List<String> words, StringBuilder current) {
        if (current.length() > 0) {
            words.add(current.toString().toLowerCase(Locale.ROOT));
            current.setLength(0);
        }
    }
}
