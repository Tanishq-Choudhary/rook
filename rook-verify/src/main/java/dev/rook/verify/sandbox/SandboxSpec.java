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
        int maxOutputBytes,
        List<Tmpfs> tmpfs) {

    public record Tmpfs(String path, int sizeMb) {

        public Tmpfs {
            if (path == null
                    || !path.startsWith("/")
                    || path.contains(":")
                    || path.contains("..")) {
                throw new IllegalArgumentException("invalid tmpfs path: " + path);
            }
            if (sizeMb < 1) {
                throw new IllegalArgumentException("tmpfs size must be positive");
            }
        }
    }

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
        tmpfs = List.copyOf(tmpfs);
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

    public SandboxSpec(
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
        this(
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
                labels,
                maxOutputBytes,
                List.of());
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
                256 * 1024,
                List.of());
    }

    public SandboxSpec withEnv(Map<String, String> value) {
        return new SandboxSpec(
                image,
                workdir,
                command,
                value,
                timeout,
                cpus,
                memoryMb,
                pids,
                network,
                mounts,
                labels,
                maxOutputBytes,
                tmpfs);
    }

    public SandboxSpec withTimeout(Duration value) {
        return new SandboxSpec(
                image,
                workdir,
                command,
                env,
                value,
                cpus,
                memoryMb,
                pids,
                network,
                mounts,
                labels,
                maxOutputBytes,
                tmpfs);
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
                maxOutputBytes,
                tmpfs);
    }

    public SandboxSpec withNetwork(NetworkMode value) {
        return new SandboxSpec(
                image,
                workdir,
                command,
                env,
                timeout,
                cpus,
                memoryMb,
                pids,
                value,
                mounts,
                labels,
                maxOutputBytes,
                tmpfs);
    }

    public SandboxSpec withMounts(List<Mount> value) {
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
                value,
                labels,
                maxOutputBytes,
                tmpfs);
    }

    public SandboxSpec withLabels(Map<String, String> value) {
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
                value,
                maxOutputBytes,
                tmpfs);
    }

    public SandboxSpec withMaxOutputBytes(int value) {
        return new SandboxSpec(
                image, workdir, command, env, timeout, cpus, memoryMb, pids, network, mounts,
                labels, value, tmpfs);
    }

    public SandboxSpec withTmpfs(List<Tmpfs> value) {
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
                labels,
                maxOutputBytes,
                value);
    }
}
