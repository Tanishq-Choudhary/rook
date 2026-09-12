package dev.rook.eval.fixture;

import java.util.List;

public record FixtureSpec(
        String id,
        String title,
        String category,
        String difficulty,
        boolean noop,
        List<String> changeKinds,
        List<ExpectedLocation> expected,
        List<String> untouchable,
        List<String> tags) {

    public record ExpectedLocation(String file, int line) {

        public String key() {
            return file + ":" + line;
        }
    }

    public FixtureSpec {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{1,80}")) {
            throw new IllegalArgumentException("fixture id must be a lowercase slug: " + id);
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("fixture title is required");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("fixture category is required");
        }
        difficulty = difficulty == null ? "easy" : difficulty;
        changeKinds = changeKinds == null ? List.of() : List.copyOf(changeKinds);
        expected = expected == null ? List.of() : List.copyOf(expected);
        untouchable = untouchable == null ? List.of() : List.copyOf(untouchable);
        tags = tags == null ? List.of() : List.copyOf(tags);
        if (noop && !expected.isEmpty()) {
            throw new IllegalArgumentException("a no-op fixture cannot expect locations");
        }
    }

    public List<String> expectedKeys() {
        return expected.stream().map(ExpectedLocation::key).sorted().toList();
    }
}
