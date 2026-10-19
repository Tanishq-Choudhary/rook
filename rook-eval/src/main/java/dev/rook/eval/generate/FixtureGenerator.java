package dev.rook.eval.generate;

import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.fixture.FixtureSpec;
import dev.rook.eval.fixture.FixtureStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FixtureGenerator {

    private FixtureGenerator() {}

    public static List<Fixture> generate(Path root) {
        return generate(root, Domain.all());
    }

    public static List<Fixture> generateHeldOut(Path root) {
        return generate(root, Domain.heldOut());
    }

    private static List<Fixture> generate(Path root, List<Domain> domains) {
        List<Fixture> fixtures = new ArrayList<>();
        for (Recipes.Recipe recipe : Recipes.all()) {
            for (Domain domain : domains) {
                for (Style style : Style.values()) {
                    fixtures.add(write(root, recipe, domain, style));
                }
            }
        }
        return List.copyOf(fixtures);
    }

    public static int count() {
        return Recipes.all().size() * Domain.all().size() * Style.values().length;
    }

    private static Fixture write(Path root, Recipes.Recipe recipe, Domain domain, Style style) {
        Blueprint blueprint = recipe.build().apply(domain, style);
        String id = recipe.slug() + "-" + domain.slug() + "-" + style.name().toLowerCase();
        Path directory = root.resolve(id);

        Map<String, String> repo =
                new LinkedHashMap<>(CustomerCode.repo(id, domain, style, blueprint.before()));
        repo.putAll(blueprint.extraRepoFiles());
        Map<String, String> goldFull = CustomerCode.repo(id, domain, style, blueprint.gold());
        Map<String, String> gold = new LinkedHashMap<>();
        goldFull.forEach(
                (path, content) -> {
                    if (!content.equals(repo.get(path))) {
                        gold.put(path, content);
                    }
                });

        store(directory.resolve("repo"), repo);
        store(directory.resolve("gold"), gold);
        store(directory.resolve("verify"), CustomerCode.verify(domain));
        store(directory.resolve("sdk/before"), blueprint.sdkBefore());
        store(directory.resolve("sdk/after"), blueprint.sdkAfter());

        FixtureSpec spec =
                new FixtureSpec(
                        id,
                        blueprint.title(),
                        blueprint.category(),
                        blueprint.difficulty(),
                        blueprint.noop(),
                        blueprint.changeKinds(),
                        expected(repo, blueprint),
                        blueprint.untouchable(),
                        List.of(domain.slug(), style.name().toLowerCase(), recipe.slug()));
        Fixture fixture = new Fixture(spec, directory);
        FixtureStore.write(fixture);
        return fixture;
    }

    private static List<FixtureSpec.ExpectedLocation> expected(
            Map<String, String> repo, Blueprint blueprint) {
        List<FixtureSpec.ExpectedLocation> locations = new ArrayList<>();
        if (blueprint.needle() == null) {
            return locations;
        }
        repo.keySet().stream()
                .filter(path -> path.startsWith("src/main/java/") && path.endsWith(".java"))
                .filter(path -> !blueprint.untouchable().contains(path))
                .sorted()
                .forEach(
                        path -> {
                            String[] lines = repo.get(path).split("\n", -1);
                            for (int i = 0; i < lines.length; i++) {
                                if (lines[i].contains(blueprint.needle())) {
                                    locations.add(new FixtureSpec.ExpectedLocation(path, i + 1));
                                }
                            }
                        });
        return locations;
    }

    private static void store(Path directory, Map<String, String> files) {
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = directory.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
