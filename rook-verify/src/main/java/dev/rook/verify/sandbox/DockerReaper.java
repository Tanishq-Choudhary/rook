package dev.rook.verify.sandbox;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class DockerReaper {

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
                                "{{.Names}} {{.Label \"" + DockerCommand.STARTED_LABEL + "\"}}"));
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
            String[] parts = line.strip().split("\\s+");
            if (parts.length != 2) {
                continue;
            }
            try {
                long started = Long.parseLong(parts[1]);
                if (nowEpoch - started >= olderThan.toSeconds()) {
                    names.add(parts[0]);
                }
            } catch (NumberFormatException e) {
                continue;
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
