package dev.rook.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.core.json.Json;
import dev.rook.eval.report.EvalReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalCliIT {

    @Test
    void aFilteredRunProducesReportsWithRealMavenVerification(@TempDir Path temp)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        assertThat(EvalCli.run(new String[] {"generate", temp.resolve("f").toString()}, out))
                .isZero();
        int code =
                EvalCli.run(
                        new String[] {
                            "run",
                            temp.resolve("f").toString(),
                            temp.resolve("r").toString(),
                            "-pay-"
                        },
                        out);
        assertThat(code).isZero();
        EvalReport report =
                Json.read(Files.readString(temp.resolve("r/report.json")), EvalReport.class);
        assertThat(report.fixtures()).isEqualTo(18);
        assertThat(report.strategies()).hasSize(1);
        assertThat(report.strategies().get(0).strategy()).isEqualTo("codemod-only");
        assertThat(report.strategies().get(0).regressionEscapes()).isZero();
        assertThat(report.strategies().get(0).noopFixturesEdited()).isZero();
        assertThat(report.strategies().get(0).protectedFilesEdited()).isZero();
        assertThat(report.notRun()).containsExactly("llm-only", "agent");
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("limitations");
        assertThat(temp.resolve("r/results-codemod-only.json")).exists();
    }
}
