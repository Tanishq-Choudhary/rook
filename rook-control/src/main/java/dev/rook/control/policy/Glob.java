package dev.rook.control.policy;

import java.util.regex.Pattern;

final class Glob {

    private Glob() {}

    static boolean matches(String pattern, String path) {
        return compile(pattern).matcher(normalize(path)).matches();
    }

    static Pattern compile(String pattern) {
        String glob = normalize(pattern);
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    boolean slashAfter = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                    regex.append(slashAfter ? "(?:.*/)?" : ".*");
                    i += slashAfter ? 3 : 2;
                    continue;
                }
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        return Pattern.compile(regex.append('$').toString());
    }

    static String normalize(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
