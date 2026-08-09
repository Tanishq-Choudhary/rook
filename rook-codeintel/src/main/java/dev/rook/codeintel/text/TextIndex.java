package dev.rook.codeintel.text;

import dev.rook.codeintel.RepoFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TextIndex {

    private static final Set<String> EXTENSIONS =
            Set.of(
                    "json",
                    "yaml",
                    "yml",
                    "properties",
                    "xml",
                    "txt",
                    "http",
                    "graphql",
                    "sql",
                    "csv");

    public record Match(String file, int line, String snippet, boolean test) {}

    private final Map<String, List<String>> files;

    private TextIndex(Map<String, List<String>> files) {
        this.files = files;
    }

    public static TextIndex build(Path root) {
        Map<String, List<String>> files = new LinkedHashMap<>();
        for (RepoFiles.RepoFile file : RepoFiles.walk(root)) {
            if (!EXTENSIONS.contains(file.extension()) || file.size() > 512 * 1024) {
                continue;
            }
            try {
                files.put(
                        file.path(),
                        List.of(
                                Files.readString(file.absolute(), StandardCharsets.UTF_8)
                                        .split("\\R", -1)));
            } catch (java.io.IOException | RuntimeException e) {
                continue;
            }
        }
        return new TextIndex(files);
    }

    public static TextIndex empty() {
        return new TextIndex(Map.of());
    }

    public List<Match> findKey(String name) {
        List<Match> matches = new ArrayList<>();
        String quoted = "\"" + name + "\"";
        for (Map.Entry<String, List<String>> file : files.entrySet()) {
            List<String> lines = file.getValue();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains(quoted) || startsWithKey(line, name)) {
                    matches.add(match(file.getKey(), i + 1, line));
                }
            }
        }
        return matches;
    }

    public List<Match> findValue(String value) {
        List<Match> matches = new ArrayList<>();
        String quoted = "\"" + value + "\"";
        String yamlValue = ": " + value;
        for (Map.Entry<String, List<String>> file : files.entrySet()) {
            List<String> lines = file.getValue();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).stripTrailing();
                if (line.contains(quoted)
                        || line.endsWith(yamlValue)
                        || line.endsWith("=" + value)) {
                    matches.add(match(file.getKey(), i + 1, lines.get(i)));
                }
            }
        }
        return matches;
    }

    private static boolean startsWithKey(String line, String name) {
        String stripped = line.stripLeading();
        return stripped.startsWith(name + ":") || stripped.startsWith(name + "=");
    }

    private static Match match(String file, int line, String text) {
        String snippet = text.strip();
        if (snippet.length() > 200) {
            snippet = snippet.substring(0, 200);
        }
        boolean test = file.startsWith("src/test/") || file.contains("/src/test/");
        return new Match(file, line, snippet, test);
    }
}
