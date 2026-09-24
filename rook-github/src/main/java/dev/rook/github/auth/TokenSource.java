package dev.rook.github.auth;

public interface TokenSource {

    String tokenFor(long installationId);

    void forget(long installationId);
}
