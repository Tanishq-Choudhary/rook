package dev.rook.eval.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureStoreTest {

    @TempDir Path temp;

    private Path layout(String id, FixtureSpec spec) throws IOException {
        Path dir = temp.resolve(id);
        put(dir, "repo/pom.xml", "<project/>");
        put(dir, "repo/src/main/java/app/Service.java", "class Service {\n}\n");
        put(dir, "sdk/before/lib/Client.java", "class Client {}");
        put(dir, "sdk/after/lib/Client.java", "class Client {}");
        put(dir, "verify/src/test/java/app/CheckTest.java", "class CheckTest {}");
        put(dir, "gold/src/main/java/app/Service.java", "class Service {\n}\n");
        FixtureStore.write(new Fixture(spec, dir));
        return dir;
    }

    private static void put(Path dir, String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static FixtureSpec spec(String id) {
        return new FixtureSpec(
                id,
                "rename a method",
                "method-rename",
                "easy",
                false,
                List.of("METHOD_RENAMED"),
                List.of(new FixtureSpec.ExpectedLocation("src/main/java/app/Service.java", 1)),
                List.of(),
                List.of("rename"));
    }

    @Test
    void aWrittenFixtureLoadsBackIdentically() throws IOException {
        Path dir = layout("rename-one", spec("rename-one"));
        Fixture loaded = FixtureStore.load(dir);
        assertThat(loaded.spec()).isEqualTo(spec("rename-one"));
        assertThat(loaded.id()).isEqualTo("rename-one");
        assertThat(loaded.goldFiles()).containsOnlyKeys("src/main/java/app/Service.java");
        assertThat(loaded.sdkBeforeFiles()).containsOnlyKeys("lib/Client.java");
        assertThat(loaded.sdkAfterFiles()).containsOnlyKeys("lib/Client.java");
        assertThat(loaded.spec().expectedKeys())
                .containsExactly("src/main/java/app/Service.java:1");
    }

    @Test
    void fixturesLoadInIdOrder() throws IOException {
        layout("b-second", spec("b-second"));
        layout("a-first", spec("a-first"));
        assertThat(FixtureStore.loadAll(temp))
                .extracting(Fixture::id)
                .containsExactly("a-first", "b-second");
    }

    @Test
    void aFolderThatDisagreesWithTheIdIsRejected() throws IOException {
        Path dir = layout("right-name", spec("right-name"));
        Path moved = Files.move(dir, temp.resolve("wrong-name"));
        assertThatThrownBy(() -> FixtureStore.load(moved))
                .isInstanceOf(FixtureException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void missingPartsAreListedTogether() throws IOException {
        Path dir = layout("broken", spec("broken"));
        Files.delete(dir.resolve("repo/pom.xml"));
        Files.delete(dir.resolve("verify/src/test/java/app/CheckTest.java"));
        assertThatThrownBy(() -> FixtureStore.load(dir))
                .isInstanceOf(FixtureException.class)
                .hasMessageContaining("repo/pom.xml is missing")
                .hasMessageContaining("independent tests");
    }

    @Test
    void expectedLocationsMustPointInsideRealFiles() throws IOException {
        FixtureSpec outside =
                new FixtureSpec(
                        "outside",
                        "t",
                        "c",
                        "easy",
                        false,
                        List.of(),
                        List.of(
                                new FixtureSpec.ExpectedLocation(
                                        "src/main/java/app/Service.java", 99),
                                new FixtureSpec.ExpectedLocation("src/main/java/app/Gone.java", 1)),
                        List.of("src/main/java/app/Absent.java"),
                        List.of());
        Path dir = layout("outside", outside);
        assertThatThrownBy(() -> FixtureStore.load(dir))
                .isInstanceOf(FixtureException.class)
                .hasMessageContaining("outside the file")
                .hasMessageContaining("does not exist")
                .hasMessageContaining("untouchable");
    }

    @Test
    void aChangeFixtureNeedsGoldButANoopDoesNot() throws IOException {
        Path dir = layout("needs-gold", spec("needs-gold"));
        Files.delete(dir.resolve("gold/src/main/java/app/Service.java"));
        assertThatThrownBy(() -> FixtureStore.load(dir)).hasMessageContaining("gold/");
        FixtureSpec noop =
                new FixtureSpec(
                        "quiet", "t", "c", "easy", true, List.of(), List.of(), List.of(),
                        List.of());
        Path quiet = layout("quiet", noop);
        Files.delete(quiet.resolve("gold/src/main/java/app/Service.java"));
        assertThat(FixtureStore.load(quiet).spec().noop()).isTrue();
    }

    @Test
    void specValidationRejectsNonsense() {
        assertThatThrownBy(() -> spec("Bad Id")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new FixtureSpec(
                                        "ok-id", " ", "c", null, false, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new FixtureSpec(
                                        "ok-id", "t", null, null, false, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        FixtureSpec defaults =
                new FixtureSpec("ok-id", "t", "c", null, false, null, null, null, null);
        assertThat(defaults.difficulty()).isEqualTo("easy");
        assertThat(defaults.tags()).isEmpty();
    }

    @Test
    void missingRootsAndSpecsAreReported() {
        assertThatThrownBy(() -> FixtureStore.loadAll(temp.resolve("nope")))
                .isInstanceOf(FixtureException.class);
        assertThatThrownBy(() -> FixtureStore.load(temp)).isInstanceOf(FixtureException.class);
    }

    @Test
    void readingATreeReturnsRelativeSlashPaths() throws IOException {
        put(temp, "t/a/b.txt", "x");
        put(temp, "t/c.txt", "y");
        assertThat(Fixture.readTree(temp.resolve("t"))).containsOnlyKeys("a/b.txt", "c.txt");
        assertThat(Fixture.readTree(temp.resolve("none"))).isEmpty();
    }
}
