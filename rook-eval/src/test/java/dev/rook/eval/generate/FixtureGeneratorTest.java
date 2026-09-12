package dev.rook.eval.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.eval.build.JavaCompilation;
import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.fixture.FixtureStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureGeneratorTest {

    @TempDir static Path shared;

    static List<Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = FixtureGenerator.generate(shared.resolve("all"));
    }

    @Test
    void everyRecipeDomainAndStyleProducesOneFixture() {
        assertThat(fixtures).hasSize(FixtureGenerator.count());
        assertThat(FixtureGenerator.count()).isEqualTo(72);
        assertThat(fixtures.stream().map(Fixture::id).collect(Collectors.toSet())).hasSize(72);
        assertThat(fixtures.stream().map(f -> f.spec().category()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "method-rename",
                        "class-move",
                        "argument-removed",
                        "parameter-type-changed",
                        "return-type-changed",
                        "deprecation-only",
                        "unrelated-update",
                        "name-collision",
                        "string-mention");
    }

    @Test
    void generatedFixturesPassTheStructuralChecks() {
        assertThat(FixtureStore.loadAll(shared.resolve("all"))).hasSize(72);
    }

    @Test
    void generationIsDeterministic() throws IOException {
        FixtureGenerator.generate(shared.resolve("again"));
        for (Fixture fixture : fixtures) {
            Path other = shared.resolve("again").resolve(fixture.id());
            assertThat(Fixture.readTree(other)).isEqualTo(Fixture.readTree(fixture.directory()));
        }
    }

    @Test
    void noopFixturesHaveNoGoldAndTheRestOverlayExistingFiles() {
        for (Fixture fixture : fixtures) {
            Map<String, String> gold = fixture.goldFiles();
            if (fixture.spec().noop()) {
                assertThat(gold).as(fixture.id()).isEmpty();
                continue;
            }
            assertThat(gold).as(fixture.id()).isNotEmpty();
            Map<String, String> repo = Fixture.readTree(fixture.repo());
            gold.forEach(
                    (path, content) -> {
                        assertThat(repo).as(fixture.id()).containsKey(path);
                        assertThat(content).as(path).isNotEqualTo(repo.get(path));
                    });
        }
    }

    @Test
    void untouchableFilesAreNeverPartOfTheGoldPatch() {
        for (Fixture fixture : fixtures) {
            for (String path : fixture.spec().untouchable()) {
                assertThat(fixture.goldFiles()).as(fixture.id()).doesNotContainKey(path);
            }
        }
        assertThat(
                        fixtures.stream()
                                .filter(f -> f.spec().category().equals("name-collision"))
                                .allMatch(f -> f.spec().untouchable().size() == 2))
                .isTrue();
    }

    @Test
    void expectedLocationsPointAtLinesThatMentionTheChangedSymbol() throws IOException {
        for (Fixture fixture : fixtures) {
            for (var location : fixture.spec().expected()) {
                String line =
                        Files.readAllLines(fixture.repo().resolve(location.file()))
                                .get(location.line() - 1);
                assertThat(line)
                        .as(fixture.id() + " " + location.key())
                        .containsAnyOf(".", "import", "Client");
            }
            if (fixture.spec().category().equals("unrelated-update")) {
                assertThat(fixture.spec().expected()).isEmpty();
            } else {
                assertThat(fixture.spec().expected()).as(fixture.id()).isNotEmpty();
            }
        }
    }

    @Test
    void theOldCustomerCodeCompilesAgainstTheOldSdkAndBreaksOnTheNewOne() {
        fixtures.parallelStream()
                .forEach(
                        fixture -> {
                            Path main = fixture.repo().resolve("src/main/java");
                            Path out = shared.resolve("c").resolve(fixture.id());
                            JavaCompilation.Result base =
                                    JavaCompilation.compile(
                                            List.of(main, fixture.sdkBefore()),
                                            List.of(),
                                            out.resolve("base"));
                            assertThat(base.errors()).as(fixture.id() + " base").isEmpty();
                            JavaCompilation.Result upgraded =
                                    JavaCompilation.compile(
                                            List.of(main, fixture.sdkAfter()),
                                            List.of(),
                                            out.resolve("upgraded"));
                            assertThat(upgraded.success())
                                    .as(fixture.id() + " upgraded")
                                    .isEqualTo(fixture.spec().noop());
                        });
    }

    @Test
    void theGoldPatchCompilesAgainstTheNewSdk() {
        fixtures.parallelStream()
                .filter(fixture -> !fixture.spec().noop())
                .forEach(
                        fixture -> {
                            Path merged = shared.resolve("g").resolve(fixture.id());
                            overlay(fixture, merged);
                            JavaCompilation.Result result =
                                    JavaCompilation.compile(
                                            List.of(merged, fixture.sdkAfter()),
                                            List.of(),
                                            shared.resolve("gc").resolve(fixture.id()));
                            assertThat(result.errors()).as(fixture.id()).isEmpty();
                        });
    }

    @Test
    void fixtureSourcesCarryNoComments() {
        Set<String> comment = Set.of("//", "/*");
        for (Fixture fixture : fixtures) {
            Fixture.readTree(fixture.directory())
                    .forEach(
                            (path, content) -> {
                                if (path.endsWith(".java") || path.endsWith(".xml")) {
                                    for (String marker : comment) {
                                        assertThat(
                                                        content.replace("http://", "")
                                                                .replace("https://", ""))
                                                .as(fixture.id() + " " + path)
                                                .doesNotContain(marker);
                                    }
                                }
                            });
        }
    }

    @Test
    void templatesRefuseUnresolvedPlaceholders() {
        assertThatThrownBy(() -> Template.render("a ${missing} b", Map.of("other", "x")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Template.render("a ${x}${x}", Map.of("x", "1"))).isEqualTo("a 11");
    }

    @Test
    void usagesRenderCallsAndSortedImports() {
        Usage usage = Usage.of("b.Client", "{c}.go({k})").withExtraImport("a.Ref");
        assertThat(usage.call("client", "key")).isEqualTo("client.go(key)");
        assertThat(usage.imports()).isEqualTo("import a.Ref;\nimport b.Client;\n");
        assertThat(usage.extraImports()).containsExactly("a.Ref");
    }

    @Test
    void domainsExposeQualifiedNamesAndSourcePaths() {
        Domain pay = Domain.all().get(0);
        assertThat(pay.qualifiedClient()).isEqualTo("paylib.PayClient");
        assertThat(pay.path("Ref")).isEqualTo("paylib/Ref.java");
        assertThat(Domain.all()).hasSize(4);
    }

    private static void overlay(Fixture fixture, Path merged) {
        try {
            copy(fixture.repo().resolve("src/main/java"), merged);
            for (Map.Entry<String, String> file : fixture.goldFiles().entrySet()) {
                if (file.getKey().startsWith("src/main/java/")) {
                    Path target =
                            merged.resolve(file.getKey().substring("src/main/java/".length()));
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, file.getValue());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
    }
}
