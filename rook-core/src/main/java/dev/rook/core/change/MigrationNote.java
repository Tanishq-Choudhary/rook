package dev.rook.core.change;

import java.util.Objects;

public record MigrationNote(String title, String body, String url) {

    public MigrationNote {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        url = url == null ? "" : url;
    }
}
