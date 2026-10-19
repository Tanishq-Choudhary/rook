package dev.rook.eval.generate;

import static org.assertj.core.api.Assertions.assertThat;

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

class HeldOutFixtureTest {

    @TempDir static Path shared;

    static List<Fixture> held;
    static List<Fixture> original;

    @BeforeAll
    static void generate() {
        held = FixtureGenerator.generateHeldOut(shared.resolve("held"));
        original = FixtureGenerator.generate(shared.resolve("original"));
    }

    @Test
    void theHeldOutSetHasTheSameShapeAsTheMainSet() {
        assertThat(held).hasSize(72);
        assertThat(held.stream().map(f -> f.spec().category()).collect(Collectors.toSet()))
                .isEqualTo(
                        original.stream()
                                .map(f -> f.spec().category())
                                .collect(Collectors.toSet()));
        assertThat(held.stream().filter(f -> f.spec().noop()).count())
                .isEqualTo(original.stream().filter(f -> f.spec().noop()).count());
    }

    @Test
    void noNameOrDomainIsSharedWithTheMainSet() {
        Set<String> originalIds = original.stream().map(Fixture::id).collect(Collectors.toSet());
        assertThat(held).noneMatch(f -> originalIds.contains(f.id()));
        Set<String> originalSlugs =
                Domain.all().stream().map(Domain::slug).collect(Collectors.toSet());
        Set<String> originalNames = Set.of("PayClient", "MailClient", "BlobClient", "GeoClient");
        for (Domain domain : Domain.heldOut()) {
            assertThat(originalSlugs).doesNotContain(domain.slug());
            assertThat(originalNames).doesNotContain(domain.client());
        }
        assertThat(Domain.heldOut().stream().map(Domain::method).collect(Collectors.toSet()))
                .doesNotContainAnyElementsOf(
                        Domain.all().stream().map(Domain::method).collect(Collectors.toSet()));
    }

    @Test
    void heldOutFixturesPassTheStructuralChecks() {
        assertThat(FixtureStore.loadAll(shared.resolve("held"))).hasSize(72);
    }

    @Test
    void heldOutGenerationIsDeterministic() {
        FixtureGenerator.generateHeldOut(shared.resolve("again"));
        for (Fixture fixture : held) {
            Path other = shared.resolve("again").resolve(fixture.id());
            assertThat(Fixture.readTree(other)).isEqualTo(Fixture.readTree(fixture.directory()));
        }
    }

    @Test
    void theOldCustomerCodeCompilesAgainstTheOldSdkAndBreaksOnTheNewOne() {
        held.parallelStream()
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
        held.parallelStream()
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
    void heldOutSourcesCarryNoComments() {
        for (Fixture fixture : held) {
            Fixture.readTree(fixture.directory())
                    .forEach(
                            (path, content) -> {
                                if (path.endsWith(".java")) {
                                    assertThat(content)
                                            .as(fixture.id() + " " + path)
                                            .doesNotContain("//");
                                    assertThat(content)
                                            .as(fixture.id() + " " + path)
                                            .doesNotContain("/*");
                                }
                            });
        }
    }

    private static void overlay(Fixture fixture, Path merged) {
        copy(fixture.repo().resolve("src/main/java"), merged);
        Map<String, String> gold = fixture.goldFiles();
        gold.forEach(
                (path, content) -> {
                    if (path.startsWith("src/main/java/")) {
                        Path target = merged.resolve(path.substring("src/main/java/".length()));
                        try {
                            Files.createDirectories(target.getParent());
                            Files.writeString(target, content);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                });
    }

    private static void copy(Path from, Path to) {
        try (var walk = Files.walk(from)) {
            for (Path source : walk.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
