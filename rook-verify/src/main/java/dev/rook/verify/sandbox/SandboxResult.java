package dev.rook.verify.sandbox;

import java.time.Duration;

public record SandboxResult(
        int exitCode,
        boolean timedOut,
        boolean oomKilled,
        boolean outputTruncated,
        String stdout,
        String stderr,
        Duration duration) {

    public SandboxResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    public boolean succeeded() {
        return exitCode == 0 && !timedOut && !oomKilled;
    }

    public String combinedOutput() {
        if (stderr.isEmpty()) {
            return stdout;
        }
        return stdout.isEmpty() ? stderr : stdout + "\n" + stderr;
    }
}
