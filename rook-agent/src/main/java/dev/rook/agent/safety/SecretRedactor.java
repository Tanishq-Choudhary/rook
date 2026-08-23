package dev.rook.agent.safety;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SecretRedactor {

    public record Result(String text, int count, Set<String> kinds) {

        public boolean changed() {
            return count > 0;
        }
    }

    private static final String PREFIX = "[REDACTED:";
    private static final Map<String, Pattern> FIXED = new LinkedHashMap<>();

    static {
        FIXED.put(
                "private-key",
                Pattern.compile(
                        "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(-----END [A-Z ]*PRIVATE KEY-----|\\z)"));
        FIXED.put("aws-access-key", Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"));
        FIXED.put(
                "github-token",
                Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})\\b"));
        FIXED.put(
                "jwt",
                Pattern.compile(
                        "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"));
        FIXED.put("slack-token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"));
        FIXED.put("stripe-key", Pattern.compile("\\b[sr]k_(live|test)_[A-Za-z0-9]{16,}"));
        FIXED.put("api-key", Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"));
        FIXED.put("google-key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"));
        FIXED.put("url-credentials", Pattern.compile("(?<=://)[^\\s/:@]+:[^\\s/@]+(?=@)"));
    }

    private static final Pattern ASSIGNMENT =
            Pattern.compile(
                    "(?i)(?<head>\\b[A-Za-z0-9_.-]*(secret|passw(or)?d|token|api[_-]?key|apikey|auth|credential|private[_-]?key)[A-Za-z0-9_.-]*[\"']?\\s*[=:]\\s*[\"']?)(?<value>[^\\s\"'#,;]{8,})");

    public String redact(String text) {
        return redactWithReport(text).text();
    }

    public Result redactWithReport(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text == null ? "" : text, 0, Set.of());
        }
        String current = text;
        int count = 0;
        TreeSet<String> kinds = new TreeSet<>();
        for (Map.Entry<String, Pattern> entry : FIXED.entrySet()) {
            Matcher matcher = entry.getValue().matcher(current);
            StringBuilder out = new StringBuilder();
            int found = 0;
            while (matcher.find()) {
                matcher.appendReplacement(
                        out, Matcher.quoteReplacement(PREFIX + entry.getKey() + "]"));
                found++;
            }
            matcher.appendTail(out);
            if (found > 0) {
                count += found;
                kinds.add(entry.getKey());
                current = out.toString();
            }
        }
        Matcher assignment = ASSIGNMENT.matcher(current);
        StringBuilder out = new StringBuilder();
        int found = 0;
        while (assignment.find()) {
            String value = assignment.group("value");
            if (looksLikeReference(value)) {
                assignment.appendReplacement(out, Matcher.quoteReplacement(assignment.group()));
                continue;
            }
            assignment.appendReplacement(
                    out, Matcher.quoteReplacement(assignment.group("head") + PREFIX + "assignment]"));
            found++;
        }
        assignment.appendTail(out);
        if (found > 0) {
            count += found;
            kinds.add("assignment");
            current = out.toString();
        }
        return new Result(current, count, Set.copyOf(kinds));
    }

    private boolean looksLikeReference(String value) {
        return value.startsWith(PREFIX)
                || value.startsWith("$")
                || value.startsWith("{")
                || value.startsWith("<")
                || value.startsWith("http://")
                || value.startsWith("https://")
                || value.contains("(")
                || value.contains(")")
                || value.contains("[REDACTED");
    }
}
