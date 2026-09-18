package dev.rook.control.auth;

import java.util.Optional;

public interface Authenticator {

    Optional<Principal> authenticate(String presented);
}
