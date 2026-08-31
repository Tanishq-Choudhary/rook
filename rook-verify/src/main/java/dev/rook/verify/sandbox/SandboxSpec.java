package dev.rook.verify.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record SandboxSpec(
        String image,
        String workdir,
        List<String> command,
        Map<String, String> env,
        Duration timeout,
        double cpus,
        int memoryMb,
        int pids,
        NetworkMode network,
        List<Mount> mounts,
        Map<String, String> labels,
        int maxOutputBytes) {

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern LABEL_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    public SandboxSpec {
        if (image == null || image.isBlank() || command == null || command.isEmpty()) {
            throw new IllegalArgumentException("a sandbox run needs an image and a command");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("the timeout must be positive");
        }
        if (cpus <= 0 || memoryMb < 16 || pids < 8 || maxOutputBytes < 1024) {
            throw new IllegalArgumentException("resource limits are too small to run anything");
        }
        command = List.copyOf(command);
        env = Map.copyOf(env);
        mounts = List.copyOf(mounts);
        labels = Map.copyOf(labels);
        env.keySet()
                .forEach(
                        name -> {
                            if (!ENV_NAME.matcher(name).matches()) {
                                throw new IllegalArgumentException(
                                        "invalid environment name: " + name);
                            }
                        });
        labels.keySet()
                .forEach(
                        key -> {
                            if (!LABEL_KEY.matcher(key).matches()) {
                                throw new IllegalArgumentException("invalid label: " + key);
                            }
                        });
    }

    public static SandboxSpec of(String image, String workdir, List<String> command) {
        return new SandboxSpec(
                image,
                workdir,
                command,
                Map.of(),
                Duration.ofMinutes(5),
                1.0,
                1024,
                256,
                NetworkMode.NONE,
                List.of(),
                Map.of(),
                256 * 1024);
    }

    public SandboxSpec withEnv(Map<String, String> newEnv) {
        return new SandboxSpec(
                image,
                workdir,
                command,
                newEnv,
                timeout,
                cpus,
                memoryMb,
                pids,
                network,
                mounts,
                labels,
                maxOutputBytes);
    }

    public SandboxSpec withTimeout(Duration newTimeout) {
        return new SandboxSpec(
                image,
                workdir,
                command,
                env,
                newTimeout,
                cpus,
                memoryMb,
                pids,
                network,
                mounts,
                labels,
                maxOutputBytes);
    }

    public SandboxSpec withLimits(double newCpus, int newMemoryMb, int newPids) {
        return new SandboxSpec(
                image,
                workdir,
                command,
                env,
                timeout,
                newCpus,
                newMemoryMb,
                newPids,
                network,
                mounts,
                labels,
                maxOutputBytes);
    }

    public SandboxSpec withNetwork(NetworkMode newNetwork) {
        return new SandboxSpec(
                image,
                workdir,
                command,
                env,
                timeout,
                cpus,
                memoryMb,
                pids,
                newNetwork,
                mounts,
                labels,
                maxOutputBytes);
    }

    public SandboxSpec withMounts(List<Mount> newMounts) {
        return new SandboxSpec(
                image,
                workdir,
                command,
                env,
                timeout,
                cpus,
                memoryMb,
                pids,
                network,
                newMounts,
                labels,
                maxOutputBytes);
    }

    public SandboxSpec withLabels(Map<String, String> newLabels) {
        return new SandboxSpec(
                image,
                workdir,
                command,
                env,
                timeout,
                cpus,
                memoryMb,
                pids,
                network,
                mounts,
                newLabels,
                maxOutputBytes);
    }

    public SandboxSpec withMaxOutputBytes(int bytes) {
        return new SandboxSpec(
                image, workdir, command, env, timeout, cpus, memoryMb, pids, network, mounts,
                labels, bytes);
    }
}
