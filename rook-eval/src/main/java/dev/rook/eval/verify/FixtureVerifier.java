package dev.rook.eval.verify;

import dev.rook.eval.fixture.Fixture;
import java.nio.file.Path;

public interface FixtureVerifier {

    String name();

    Verdict verify(Fixture fixture, Path repoRoot, SdkVersion sdk);
}
