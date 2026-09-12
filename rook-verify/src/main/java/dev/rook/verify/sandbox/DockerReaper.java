package dev.rook.verify.sandbox;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DockerReaper {

    private static final Pattern STARTED = Pattern.compile("(?:^|,)rook\\.started=(\\d+)(?:,|$)");

    private final DockerConfig config;
    private final Clock clock;
    private final LocalProcessRunner cli = DockerCli.runner();

    public DockerReaper(DockerConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public int reap(Duration olderThan) {
        SandboxResult listing =
                run(
                        List.of(
                                config.binary(),
                                "ps",
                                "--all",
                                "--filter",
                                "label=" + DockerCommand.MANAGED_LABEL + "=true",
                                "--format",
                                "{{.Names}}|{{.Labels}}"));
        if (!listing.succeeded()) {
            return 0;
        }
        List<String> stale = stale(listing.stdout(), clock.instant().getEpochSecond(), olderThan);
        for (String name : stale) {
            run(List.of(config.binary(), "rm", "--force", "--volumes", name));
        }
        return stale.size();
    }

    static List<String> stale(String listing, long nowEpoch, Duration olderThan) {
        List<String> names = new ArrayList<>();
        for (String line : listing.split("\\R")) {
            int bar = line.indexOf('|');
            if (bar <= 0) {
                continue;
            }
            Matcher matcher = STARTED.matcher(line.substring(bar + 1).strip());
            if (!matcher.find()) {
                continue;
            }
            long started = Long.parseLong(matcher.group(1));
            if (nowEpoch - started >= olderThan.toSeconds()) {
                names.add(line.substring(0, bar).strip());
            }
        }
        return names;
    }

    private SandboxResult run(List<String> command) {
        return cli.run(
                SandboxSpec.of("docker-cli", Path.of("").toAbsolutePath().toString(), command)
                        .withTimeout(Duration.ofSeconds(30)));
    }
}
