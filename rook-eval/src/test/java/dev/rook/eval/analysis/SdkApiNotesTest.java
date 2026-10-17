package dev.rook.eval.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.changes.jar.ApiSurface;
import dev.rook.changes.jar.ApiSurfaceReader;
import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.generate.FixtureGenerator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdkApiNotesTest {

    @TempDir static Path shared;

    static List<Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = FixtureGenerator.generate(shared.resolve("fixtures"));
    }

    private static String notes(String id, Path work) {
        Fixture fixture =
                fixtures.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
        SdkChange change = SdkChange.detect(fixture, work);
        ApiSurface surface = ApiSurfaceReader.read(change.afterJar());
        return SdkApiNotes.render(change.events(), surface);
    }

    @Test
    void aParameterTypeChangeShowsTheNewTypeAndHowToBuildIt(@TempDir Path work) {
        String text = notes("parameter-type-changed-blob-field", work);

        assertThat(text).startsWith("The SDK after the change exposes");
        assertThat(text).contains("class blobkit.BlobClient").contains("upload(blobkit.Ref)");
        assertThat(text).contains("class blobkit.Ref").contains("Ref(String)");
    }

    @Test
    void aMethodRenameShowsTheNewName(@TempDir Path work) {
        String text = notes("method-rename-pay-field", work);

        assertThat(text).contains("createCharge");
    }

    @Test
    void membersThatAreNotPublicAreLeftOut(@TempDir Path work) {
        String text = notes("parameter-type-changed-blob-field", work);

        assertThat(text).doesNotContain("<clinit>").doesNotContain("private");
    }

    @Test
    void noEventsAndNoMatchingTypesGiveNothing() {
        assertThat(SdkApiNotes.render(List.of(), new ApiSurface(java.util.Map.of()))).isEmpty();
    }

    @Test
    void theOutputIsBoundedForLargeSurfaces(@TempDir Path work) {
        String text = notes("method-rename-pay-field", work);

        assertThat(text.lines().count()).isLessThanOrEqualTo(61);
    }
}
