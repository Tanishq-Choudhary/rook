package dev.rook.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.eval.fixture.FixtureStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalCliTest {

    private static String capture(String[] args, int[] code) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        code[0] = EvalCli.run(args, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void missingArgumentsPrintTheUsage() {
        int[] code = new int[1];
        assertThat(capture(new String[0], code)).contains("usage:");
        assertThat(code[0]).isEqualTo(2);
    }

    @Test
    void unknownCommandsAreRejected() {
        int[] code = new int[1];
        assertThat(capture(new String[] {"explode", "x"}, code))
                .contains("unknown command explode");
        assertThat(code[0]).isEqualTo(2);
    }

    @Test
    void generateWritesEverySeventyTwoFixtures(@TempDir Path temp) {
        int[] code = new int[1];
        String output = capture(new String[] {"generate", temp.resolve("f").toString()}, code);
        assertThat(code[0]).isZero();
        assertThat(output).contains("generated 72 fixtures");
        assertThat(FixtureStore.loadAll(temp.resolve("f"))).hasSize(72);
    }

    @Test
    void runningOverAMissingDirectoryReportsAFailure(@TempDir Path temp) {
        int[] code = new int[1];
        String output = capture(new String[] {"run", temp.resolve("none").toString()}, code);
        assertThat(code[0]).isEqualTo(1);
        assertThat(output).contains("failed:");
    }
}
