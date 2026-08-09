package dev.rook.codeintel.project;

import dev.rook.core.error.RookException;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

public record RookConfig(
        Integer version, List<Watch> watch, Paths paths, Verify verify, Limits limits) {

    private static final Pattern SOURCE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern COORDINATES = Pattern.compile("[\\w.-]+:[\\w.-]+");
    private static final YAMLMapper MAPPER =
            YAMLMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public RookConfig {
        version = version == null ? 1 : version;
        watch = watch == null ? List.of() : List.copyOf(watch);
        paths = paths == null ? new Paths(null, null) : paths;
        verify = verify == null ? new Verify(null, null) : verify;
        limits = limits == null ? new Limits(null, null) : limits;
        validate(version, watch);
    }

    public record Watch(String source, String coordinates) {

        public Watch {
            if (source == null || !SOURCE_ID.matcher(source).matches()) {
                throw RookException.invalid("watch.source must match " + SOURCE_ID.pattern());
            }
            if (coordinates != null && !COORDINATES.matcher(coordinates).matches()) {
                throw RookException.invalid("watch.coordinates must look like group:artifact");
            }
        }
    }

    public record Paths(List<String> include, List<String> exclude) {

        public Paths {
            include = checkPaths("paths.include", include);
            exclude = checkPaths("paths.exclude", exclude);
        }

        private static List<String> checkPaths(String field, List<String> values) {
            if (values == null) {
                return List.of();
            }
            if (values.size() > 50) {
                throw RookException.invalid(field + " has more than 50 entries");
            }
            for (String value : values) {
                String normalized = value.replace('\\', '/');
                if (normalized.isBlank()
                        || normalized.startsWith("/")
                        || normalized.matches("^[A-Za-z]:.*")
                        || normalized.contains("../")
                        || normalized.equals("..")
                        || normalized.indexOf('\0') >= 0) {
                    throw RookException.invalid(field + " contains an unsafe path: " + value);
                }
            }
            return List.copyOf(values);
        }
    }

    public record Verify(String command, Integer timeoutSeconds) {

        public Verify {
            if (command != null) {
                if (command.isBlank()
                        || command.length() > 500
                        || command.indexOf('\n') >= 0
                        || command.indexOf('\0') >= 0) {
                    throw RookException.invalid(
                            "verify.command must be a single line up to 500 characters");
                }
            }
            timeoutSeconds = timeoutSeconds == null ? 600 : timeoutSeconds;
            if (timeoutSeconds < 30 || timeoutSeconds > 3600) {
                throw RookException.invalid("verify.timeoutSeconds must be between 30 and 3600");
            }
        }
    }

    public record Limits(Integer maxFilesChanged, Integer maxLinesChanged) {

        public Limits {
            maxFilesChanged = maxFilesChanged == null ? 25 : maxFilesChanged;
            maxLinesChanged = maxLinesChanged == null ? 500 : maxLinesChanged;
            if (maxFilesChanged < 1 || maxFilesChanged > 1000) {
                throw RookException.invalid("limits.maxFilesChanged must be between 1 and 1000");
            }
            if (maxLinesChanged < 1 || maxLinesChanged > 20000) {
                throw RookException.invalid("limits.maxLinesChanged must be between 1 and 20000");
            }
        }
    }

    public static RookConfig defaults() {
        return new RookConfig(null, null, null, null, null);
    }

    public static RookConfig parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return defaults();
        }
        try {
            RookConfig parsed = MAPPER.readValue(yaml, RookConfig.class);
            return parsed == null ? defaults() : parsed;
        } catch (JacksonException e) {
            throw RookException.invalid(
                    ".rook.yml is invalid: " + firstLine(e.getOriginalMessage()), e);
        }
    }

    private static void validate(int version, List<Watch> watch) {
        if (version != 1) {
            throw RookException.invalid("unsupported .rook.yml version " + version);
        }
        if (watch.size() > 50) {
            throw RookException.invalid("watch has more than 50 entries");
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
