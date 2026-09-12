package dev.rook.verify.sandbox;

import dev.rook.core.id.Ids;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DockerSandboxRunner implements SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxRunner.class);

    private static final long WATCH_INTERVAL_MILLIS = 400;

    private final DockerConfig config;
    private final Clock clock;
    private final LocalProcessRunner cli = DockerCli.runner();

    public DockerSandboxRunner(DockerConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    @Override
    public String kind() {
        return "docker";
    }

    public boolean available() {
        try {
            return exec(
                            List.of(config.binary(), "version", "--format", "{{.Server.Version}}"),
                            Duration.ofSeconds(15))
                    .succeeded();
        } catch (SandboxException e) {
            return false;
        }
    }

    @Override
    public SandboxResult run(SandboxSpec spec) {
        String name = "rook-" + Ids.newId().toString().replace("-", "").substring(0, 20);
        List<String> command =
                DockerCommand.run(spec, name, config, clock.instant().getEpochSecond());
        long started = System.nanoTime();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean diskExceeded = new AtomicBoolean();
        Thread watchdog = watch(spec, name, finished, diskExceeded);
        try {
            SandboxResult attached =
                    cli.run(
                            SandboxSpec.of("docker-cli", workingDirectory(), command)
                                    .withTimeout(spec.timeout())
                                    .withMaxOutputBytes(spec.maxOutputBytes()));
            finished.set(true);
            boolean timedOut = attached.timedOut();
            if (timedOut) {
                kill(name);
            }
            boolean oom = false;
            int exit = attached.exitCode();
            SandboxResult inspected = inspect(name);
            if (inspected != null) {
                String[] parts = inspected.stdout().strip().split("\\|");
                if (parts.length == 2) {
                    oom = Boolean.parseBoolean(parts[0]);
                    if (!timedOut) {
                        exit = Integer.parseInt(parts[1]);
                    }
                }
            }
            return new SandboxResult(
                    timedOut ? 124 : exit,
                    timedOut,
                    oom,
                    attached.outputTruncated(),
                    attached.stdout(),
                    attached.stderr(),
                    Duration.ofNanos(System.nanoTime() - started),
                    diskExceeded.get());
        } finally {
            finished.set(true);
            stop(watchdog);
            remove(name);
        }
    }

    private Thread watch(
            SandboxSpec spec, String name, AtomicBoolean finished, AtomicBoolean diskExceeded) {
        List<Path> writable =
                spec.mounts().stream().filter(m -> !m.readOnly()).map(Mount::host).toList();
        if (writable.isEmpty()) {
            return null;
        }
        WorkspaceQuota quota =
                new WorkspaceQuota(config.maxWorkspaceBytes(), config.maxWorkspaceFiles());
        return Thread.ofVirtual()
                .name("sandbox-quota-" + name)
                .start(
                        () -> {
                            while (!finished.get()) {
                                if (quota.exceeded(writable)) {
                                    diskExceeded.set(true);
                                    kill(name);
                                    return;
                                }
                                try {
                                    Thread.sleep(WATCH_INTERVAL_MILLIS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        });
    }

    private static void stop(Thread watchdog) {
        if (watchdog == null) {
            return;
        }
        watchdog.interrupt();
        try {
            watchdog.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String workingDirectory() {
        return Path.of("").toAbsolutePath().toString();
    }

    private SandboxResult exec(List<String> command, Duration timeout) {
        return cli.run(
                SandboxSpec.of("docker-cli", workingDirectory(), command).withTimeout(timeout));
    }

    private void kill(String name) {
        try {
            exec(List.of(config.binary(), "kill", name), config.killGrace());
        } catch (SandboxException e) {
            log.warn("could not kill container {}: {}", name, e.getMessage());
        }
    }

    private SandboxResult inspect(String name) {
        try {
            SandboxResult result =
                    exec(
                            List.of(
                                    config.binary(),
                                    "inspect",
                                    "--format",
                                    "{{.State.OOMKilled}}|{{.State.ExitCode}}",
                                    name),
                            config.killGrace());
            return result.succeeded() ? result : null;
        } catch (SandboxException e) {
            return null;
        }
    }

    private void remove(String name) {
        try {
            exec(List.of(config.binary(), "rm", "--force", "--volumes", name), config.killGrace());
        } catch (SandboxException e) {
            log.warn("could not remove container {}: {}", name, e.getMessage());
        }
    }
}
