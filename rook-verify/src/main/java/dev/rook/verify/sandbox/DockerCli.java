package dev.rook.verify.sandbox;

import java.util.Set;

final class DockerCli {

    private static final Set<String> ENVIRONMENT =
            Set.of(
                    "PATH",
                    "SystemRoot",
                    "SYSTEMROOT",
                    "TEMP",
                    "TMP",
                    "HOME",
                    "USERPROFILE",
                    "DOCKER_HOST",
                    "DOCKER_CONFIG",
                    "DOCKER_CONTEXT");

    private DockerCli() {}

    static LocalProcessRunner runner() {
        return LocalProcessRunner.create(true, ENVIRONMENT);
    }
}
