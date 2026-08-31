package dev.rook.verify.sandbox;

import java.nio.file.Path;

public record Mount(Path host, String container, boolean readOnly) {

    public Mount {
        if (host == null || container == null || !container.startsWith("/")) {
            throw new IllegalArgumentException(
                    "a mount needs a host path and an absolute container path");
        }
        if (container.contains(":") || container.contains("..")) {
            throw new IllegalArgumentException("invalid container path: " + container);
        }
    }

    public static Mount readOnly(Path host, String container) {
        return new Mount(host, container, true);
    }

    public static Mount readWrite(Path host, String container) {
        return new Mount(host, container, false);
    }
}
