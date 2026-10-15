package dev.rook.loadtest;

import dev.rook.core.json.Json;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class LoadCli {

    private LoadCli() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
        System.exit(run(args, out));
    }

    public static int run(String[] args, PrintStream out) {
        Map<String, String> options = parse(args);
        String scenarioName = options.get("scenario");
        if (scenarioName == null || options.get("base") == null) {
            out.println(
                    "usage: --scenario api-read|webhook --base URL [--key K] [--secret S]"
                            + " [--concurrency 16] [--seconds 20] [--warmup 5] [--out FILE]");
            return 2;
        }
        try {
            Scenario scenario =
                    switch (scenarioName) {
                        case "api-read" ->
                                Scenarios.apiRead(options.get("base"), required(options, "key"));
                        case "webhook" ->
                                Scenarios.webhook(
                                        options.get("base"),
                                        required(options, "secret"),
                                        UUID.randomUUID().toString().substring(0, 8));
                        default -> throw new IllegalArgumentException("unknown scenario");
                    };
            LoadRunner.Result result =
                    new LoadRunner()
                            .run(
                                    scenario,
                                    Integer.parseInt(options.getOrDefault("concurrency", "16")),
                                    Duration.ofSeconds(
                                            Long.parseLong(options.getOrDefault("warmup", "5"))),
                                    Duration.ofSeconds(
                                            Long.parseLong(options.getOrDefault("seconds", "20"))));
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("recordedAt", Instant.now().toString());
            document.put("javaVersion", System.getProperty("java.version"));
            document.put("processors", Runtime.getRuntime().availableProcessors());
            document.put("result", result);
            String json = Json.write(document);
            out.println(json);
            if (options.containsKey("out")) {
                Path target = Path.of(options.get("out"));
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, json + System.lineSeparator());
            }
            return result.failures() == 0 ? 0 : 1;
        } catch (IllegalArgumentException | IOException e) {
            out.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            out.println("load run failed: " + e);
            return 1;
        }
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        return options;
    }
}
