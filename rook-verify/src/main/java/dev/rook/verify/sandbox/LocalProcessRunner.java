package dev.rook.verify.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalProcessRunner implements SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessRunner.class);

    public static final Set<String> DEFAULT_INHERITED =
            Set.of(
                    "PATH",
                    "JAVA_HOME",
                    "MAVEN_HOME",
                    "M2_HOME",
                    "SystemRoot",
                    "SYSTEMROOT",
                    "TEMP",
                    "TMP",
                    "TMPDIR",
                    "HOME",
                    "USERPROFILE");

    private final Set<String> inherited;

    private LocalProcessRunner(Set<String> inherited) {
        this.inherited = inherited;
    }

    public static LocalProcessRunner create(boolean allowUnsafeLocal) {
        return create(allowUnsafeLocal, DEFAULT_INHERITED);
    }

    public static LocalProcessRunner create(boolean allowUnsafeLocal, Set<String> inherited) {
        if (!allowUnsafeLocal) {
            throw new SandboxException(
                    "the local process runner has no isolation and is only for trusted fixtures;"
                            + " set rook.sandbox.allow-unsafe-local=true to use it");
        }
        return new LocalProcessRunner(Set.copyOf(inherited));
    }

    @Override
    public String kind() {
        return "local";
    }

    @Override
    public SandboxResult run(SandboxSpec spec) {
        Path workdir = Path.of(spec.workdir());
        if (!Files.isDirectory(workdir)) {
            throw new SandboxException("working directory does not exist: " + spec.workdir());
        }
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(spec.command()));
        builder.directory(workdir.toFile());
        builder.environment().clear();
        Map<String, String> parent = System.getenv();
        for (String name : inherited) {
            if (parent.containsKey(name)) {
                builder.environment().put(name, parent.get(name));
            }
        }
        builder.environment().putAll(spec.env());
        long started = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new SandboxException("could not start " + spec.command().get(0), e);
        }
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            log.debug("stdin was already closed: {}", e.getMessage());
        }
        int capacity = Math.max(1024, spec.maxOutputBytes() / 2);
        OutputCapture out = new OutputCapture(process.getInputStream(), capacity);
        OutputCapture err = new OutputCapture(process.getErrorStream(), capacity);
        Thread outThread = Thread.ofVirtual().name("sandbox-stdout").start(out);
        Thread errThread = Thread.ofVirtual().name("sandbox-stderr").start(err);
        boolean timedOut = false;
        int exit;
        try {
            if (!process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                killTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            exit = process.isAlive() ? -1 : process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            throw new SandboxException("interrupted while running " + spec.command().get(0), e);
        }
        join(outThread);
        join(errThread);
        return new SandboxResult(
                timedOut ? 124 : exit,
                timedOut,
                false,
                out.truncated() || err.truncated(),
                out.text(),
                err.text(),
                Duration.ofNanos(System.nanoTime() - started));
    }

    private static void killTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void join(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
