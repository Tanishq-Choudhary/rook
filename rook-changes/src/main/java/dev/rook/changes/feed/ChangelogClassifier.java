package dev.rook.changes.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChangelogClassifier {

    public enum Category {
        BREAKING,
        REMOVAL,
        RENAME,
        DEPRECATION
    }

    public record Finding(
            Category category,
            String section,
            String text,
            List<String> symbols,
            String from,
            String replacement) {

        public Finding {
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
        }
    }

    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern BOLD_HEADING = Pattern.compile("^\\*\\*([^*]+)\\*\\*:?\\s*$");
    private static final Pattern SYMBOL = Pattern.compile("`([^`]+)`");
    private static final Pattern RENAMED =
            Pattern.compile(
                    "(?i)`([^`]+)`\\s+(?:has been |was |is |got )?renamed\\s+to\\s+`([^`]+)`");
    private static final Pattern USE_INSTEAD =
            Pattern.compile(
                    "(?i)(?:use|prefer|switch to|migrate to)\\s+`([^`]+)`\\s+instead(?:\\s+of\\s+`([^`]+)`)?");
    private static final Pattern REPLACED =
            Pattern.compile(
                    "(?i)`([^`]+)`\\s+(?:has been |was |is |got )?replaced\\s+(?:by|with)\\s+`([^`]+)`");
    private static final Pattern FAVOUR =
            Pattern.compile(
                    "(?i)`([^`]+)`\\s+(?:is |has been |was |are )?(?:now )?(?:deprecated|removed)[^`]*?(?:in favou?r of|replaced by|use|see)\\s+`([^`]+)`");
    private static final Pattern ARROW =
            Pattern.compile("`([^`]+)`\\s*(?:->|=>|\\u2192)\\s*`([^`]+)`");

    private ChangelogClassifier() {}

    public static List<Finding> classify(String markdown) {
        List<Finding> findings = new ArrayList<>();
        Category sectionCategory = null;
        String sectionName = "";
        StringBuilder current = null;
        for (String line : markdown.split("\\R")) {
            Matcher heading = HEADING.matcher(line);
            Matcher bold = BOLD_HEADING.matcher(line.strip());
            if (heading.matches() || bold.matches()) {
                flush(current, sectionCategory, sectionName, findings);
                current = null;
                sectionName = heading.matches() ? heading.group(1).strip() : bold.group(1).strip();
                sectionCategory = categoryFromText(sectionName);
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                flush(current, sectionCategory, sectionName, findings);
                current = new StringBuilder(bullet.group(1).strip());
                continue;
            }
            if (line.isBlank()) {
                flush(current, sectionCategory, sectionName, findings);
                current = null;
                continue;
            }
            if (current != null && Character.isWhitespace(line.charAt(0))) {
                current.append(' ').append(line.strip());
            } else {
                flush(current, sectionCategory, sectionName, findings);
                current = new StringBuilder(line.strip());
            }
        }
        flush(current, sectionCategory, sectionName, findings);
        return findings;
    }

    private static void flush(
            StringBuilder text, Category sectionCategory, String section, List<Finding> sink) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String line = text.toString();
        Category category = categoryFromText(line);
        if (category == null) {
            category = sectionCategory;
        }
        if (category == null) {
            return;
        }
        List<String> symbols = new ArrayList<>();
        Matcher matcher = SYMBOL.matcher(line);
        while (matcher.find()) {
            symbols.add(matcher.group(1));
        }
        String from = null;
        String replacement = null;
        Matcher renamed = RENAMED.matcher(line);
        Matcher arrow = ARROW.matcher(line);
        Matcher replaced = REPLACED.matcher(line);
        Matcher favour = FAVOUR.matcher(line);
        Matcher instead = USE_INSTEAD.matcher(line);
        if (renamed.find()) {
            from = renamed.group(1);
            replacement = renamed.group(2);
            category = Category.RENAME;
        } else if (replaced.find()) {
            from = replaced.group(1);
            replacement = replaced.group(2);
            category = Category.RENAME;
        } else if (favour.find()) {
            from = favour.group(1);
            replacement = favour.group(2);
        } else if (arrow.find()) {
            from = arrow.group(1);
            replacement = arrow.group(2);
        } else if (instead.find()) {
            replacement = instead.group(1);
            from = instead.group(2);
        }
        if (from == null && !symbols.isEmpty() && category != Category.BREAKING) {
            from = symbols.get(0);
        }
        sink.add(new Finding(category, section, line, symbols, from, replacement));
    }

    private static Category categoryFromText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("renamed")
                || lower.matches(".*`\\s+(has been |was |is )?replaced (by|with) `.*")) {
            return Category.RENAME;
        }
        if (lower.contains("deprecat")) {
            return Category.DEPRECATION;
        }
        if (lower.contains("breaking")) {
            return Category.BREAKING;
        }
        if (lower.matches(".*\\b(removed|removes|remove|dropped|drops|no longer)\\b.*")) {
            return Category.REMOVAL;
        }
        return null;
    }
}
