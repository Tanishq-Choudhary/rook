package dev.rook.verify.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportExportTest {

    @TempDir Path temp;

    @Test
    void theScriptCopiesTheSourceRunsTheQuotedCommandAndExportsReports() {
        String script = ReportExport.script(List.of("mvn", "-B", "-Dtest=A#b", "it's"));
        assertThat(script)
                .startsWith("cp -R /src/. /work/ || exit 97; cd /work || exit 98; ")
                .contains("'mvn' '-B' '-Dtest=A#b' 'it'\\''s'")
                .contains("code=$?")
                .contains(ReportExport.BEGIN)
                .contains(ReportExport.END)
                .contains("surefire-reports/TEST-*.xml")
                .contains("head -c " + ReportExport.MAX_ENCODED_BYTES)
                .endsWith("exit $code");
    }

    @Test
    void quotingNeutralisesShellMetacharacters() {
        assertThat(ReportExport.quote("a b; rm -rf /")).isEqualTo("'a b; rm -rf /'");
        assertThat(ReportExport.quote("$(id)")).isEqualTo("'$(id)'");
    }

    @Test
    void reportsAreExtractedFromTheLastBlockOfTheOutput() throws IOException {
        String first =
                new TestArchive()
                        .add("old/surefire-reports/TEST-a.xml", "<old/>")
                        .asOutput("noise");
        String second =
                new TestArchive()
                        .add("m/target/surefire-reports/TEST-b.xml", "<testsuite/>")
                        .asOutput(first + "more build output");
        int files = ReportExport.extract(second, temp);
        assertThat(files).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("m/target/surefire-reports/TEST-b.xml")))
                .isEqualTo("<testsuite/>");
        assertThat(Files.exists(temp.resolve("old"))).isFalse();
    }

    @Test
    void missingOrEmptyBlocksYieldNothing() throws IOException {
        assertThat(ReportExport.extract("just build output", temp)).isZero();
        assertThat(ReportExport.extract(ReportExport.BEGIN + "\n\n" + ReportExport.END, temp))
                .isZero();
    }

    @Test
    void corruptPayloadsAreReported() {
        String notGzip = ReportExport.BEGIN + "\nAAA=\n" + ReportExport.END;
        String notBase64 = ReportExport.BEGIN + "\nA\n" + ReportExport.END;
        assertThatThrownBy(() -> ReportExport.extract(notGzip, temp))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ReportExport.extract(notBase64, temp))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void anArchiveWithTraversalPathsIsRejected() {
        String hostile = new TestArchive().add("../escape.xml", "x").asOutput("");
        assertThatThrownBy(() -> ReportExport.extract(hostile, temp.resolve("out")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unsafe path");
        assertThat(Files.exists(temp.resolve("escape.xml"))).isFalse();
    }

    @Test
    void absolutePathsAreRejectedToo() {
        String hostile = new TestArchive().add("/etc/cron.d/x", "x").asOutput("");
        assertThatThrownBy(() -> ReportExport.extract(hostile, temp.resolve("out")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void longPathsUseTheGnuLongNameRecord() throws IOException {
        String longPath = "module/" + "deep/".repeat(30) + "target/surefire-reports/TEST-x.xml";
        assertThat(longPath.length()).isGreaterThan(100);
        new TarExtractor(100, 10_000_000)
                .extract(new TestArchive().add(longPath, "<ok/>").gzipped(), temp);
        assertThat(Files.readString(temp.resolve(longPath))).isEqualTo("<ok/>");
    }

    @Test
    void sizeAndCountCapsAreEnforced() {
        TestArchive many = new TestArchive();
        for (int i = 0; i < 6; i++) {
            many.add("f" + i + ".xml", "x");
        }
        assertThatThrownBy(
                        () -> new TarExtractor(5, 1000).extract(many.gzipped(), temp.resolve("a")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("too large");
        TestArchive big = new TestArchive().add("big.xml", "x".repeat(5000));
        assertThatThrownBy(
                        () -> new TarExtractor(10, 1000).extract(big.gzipped(), temp.resolve("b")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void truncatedArchivesAreDetected() throws IOException {
        byte[] full = new TestArchive().add("a.xml", "x".repeat(2000)).tar();
        byte[] cut = Arrays.copyOf(full, 700);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(cut);
        }
        assertThatThrownBy(
                        () ->
                                new TarExtractor(10, 100_000)
                                        .extract(out.toByteArray(), temp.resolve("t")))
                .isInstanceOf(IOException.class);
    }
}
