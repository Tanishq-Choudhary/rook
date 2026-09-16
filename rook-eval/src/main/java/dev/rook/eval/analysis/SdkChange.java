package dev.rook.eval.analysis;

import dev.rook.changes.jar.ApiDiffer;
import dev.rook.changes.jar.ApiSurface;
import dev.rook.changes.jar.ApiSurfaceReader;
import dev.rook.core.change.ChangeEvent;
import dev.rook.eval.build.JarBuilder;
import dev.rook.eval.build.JavaCompilation;
import dev.rook.eval.fixture.Fixture;
import java.nio.file.Path;
import java.util.List;

public record SdkChange(List<ChangeEvent> events, Path beforeJar, Path afterJar) {

    public static final String SOURCE_ID = "sdk";

    public SdkChange {
        events = List.copyOf(events);
    }

    public static SdkChange detect(Fixture fixture, Path work) {
        Path before = jar(fixture.sdkBefore(), work, "before");
        Path after = jar(fixture.sdkAfter(), work, "after");
        ApiSurface oldSurface = ApiSurfaceReader.read(before);
        ApiSurface newSurface = ApiSurfaceReader.read(after);
        List<ChangeEvent> events =
                ApiDiffer.diff(SOURCE_ID, "1.0.0", "2.0.0", oldSurface, newSurface);
        return new SdkChange(events, before, after);
    }

    private static Path jar(Path sources, Path work, String name) {
        JavaCompilation.Result result =
                JavaCompilation.compile(
                        List.of(sources), List.of(), work.resolve(name + "-classes"));
        if (!result.success()) {
            throw new IllegalStateException(
                    "sdk " + name + " does not compile: " + String.join("; ", result.errors()));
        }
        return JarBuilder.fromClasses(result.classes(), work.resolve(name + ".jar"));
    }
}
