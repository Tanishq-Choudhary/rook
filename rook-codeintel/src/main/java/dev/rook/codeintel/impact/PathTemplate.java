package dev.rook.codeintel.impact;

import java.util.regex.Pattern;

final class PathTemplate {

    private static final Pattern VERSION = Pattern.compile("^/v\\d+(?=/)");

    private final String template;
    private final Pattern full;
    private final String staticPrefix;
    private final String withoutVersion;
    private final Pattern fullWithoutVersion;

    PathTemplate(String template) {
        this.template = template;
        this.full = toPattern(template);
        int brace = template.indexOf('{');
        this.staticPrefix = brace < 0 ? template : template.substring(0, brace);
        String stripped = VERSION.matcher(template).replaceFirst("");
        this.withoutVersion = stripped.equals(template) ? null : stripped;
        this.fullWithoutVersion = withoutVersion == null ? null : toPattern(withoutVersion);
    }

    boolean matches(String value) {
        return full.matcher(value).matches();
    }

    boolean matchesPrefix(String value) {
        if (template.indexOf('{') < 0 || staticPrefix.length() < 4) {
            return false;
        }
        return value.equals(staticPrefix)
                || (value.startsWith(staticPrefix)
                        && !matches(value)
                        && value.length() <= template.length());
    }

    boolean matchesWithoutVersion(String value) {
        return fullWithoutVersion != null
                && withoutVersion.length() > 3
                && fullWithoutVersion.matcher(value).matches();
    }

    private static Pattern toPattern(String template) {
        StringBuilder regex = new StringBuilder("^");
        int index = 0;
        while (index < template.length()) {
            char c = template.charAt(index);
            if (c == '{') {
                int close = template.indexOf('}', index);
                if (close < 0) {
                    regex.append(Pattern.quote(template.substring(index)));
                    break;
                }
                regex.append("[^/]+");
                index = close + 1;
                continue;
            }
            int next = template.indexOf('{', index);
            String literal = next < 0 ? template.substring(index) : template.substring(index, next);
            regex.append(Pattern.quote(literal));
            index += literal.length();
        }
        return Pattern.compile(regex.append("/?(\\?.*)?$").toString());
    }
}
