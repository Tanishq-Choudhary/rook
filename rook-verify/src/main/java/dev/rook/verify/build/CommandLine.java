package dev.rook.verify.build;

import dev.rook.core.error.RookException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public final class CommandLine {

    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_./=:,@+%-]+");
    private static final Set<String> EXECUTABLES =
            Set.of("mvn", "./mvnw", "mvnw", "gradle", "./gradlew", "gradlew");

    private static final Set<String> FORBIDDEN_FLAGS =
            Set.of(
                    "-s",
                    "--settings",
                    "-gs",
                    "--global-settings",
                    "-t",
                    "--toolchains",
                    "-gt",
                    "--global-toolchains");

    private CommandLine() {}

    public static List<String> parse(String command) {
        if (command == null || command.isBlank()) {
            throw RookException.invalid("the verify command is empty");
        }
        List<String> tokens = new ArrayList<>();
        for (String token : command.trim().split("\\s+")) {
            if (!SAFE_TOKEN.matcher(token).matches()) {
                throw RookException.invalid(
                        "the verify command may only use plain arguments, found: "
                                + shorten(token));
            }
            tokens.add(token);
        }
        if (!EXECUTABLES.contains(tokens.get(0))) {
            throw RookException.invalid(
                    "the verify command must start with one of "
                            + String.join(", ", new TreeSet<>(EXECUTABLES)));
        }
        for (String token : tokens) {
            if (FORBIDDEN_FLAGS.contains(token)
                    || FORBIDDEN_FLAGS.stream().anyMatch(f -> token.startsWith(f + "="))) {
                throw RookException.invalid("the verify command cannot use " + token);
            }
            if (token.startsWith("-D")
                    && token.toLowerCase(Locale.ROOT).contains("maven.repo.local")) {
                throw RookException.invalid(
                        "the verify command cannot change the dependency cache");
            }
        }
        return List.copyOf(tokens);
    }

    private static String shorten(String token) {
        return token.length() <= 30 ? token : token.substring(0, 30);
    }
}
