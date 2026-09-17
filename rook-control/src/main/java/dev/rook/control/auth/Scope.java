package dev.rook.control.auth;

public enum Scope {
    READ,
    WRITE,
    ADMIN;

    public boolean implies(Scope other) {
        return this == ADMIN || compareTo(other) >= 0;
    }
}
