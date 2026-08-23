package dev.rook.agent.patch;

import java.util.Objects;

public record EditBlock(String path, String search, String replace) {

    public EditBlock {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(search, "search");
        Objects.requireNonNull(replace, "replace");
    }
}
