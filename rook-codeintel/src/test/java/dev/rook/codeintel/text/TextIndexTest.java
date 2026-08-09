package dev.rook.codeintel.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextIndexTest {

    @TempDir Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void findsJsonKeysYamlKeysAndPropertyKeys() throws IOException {
        write("src/test/resources/a.json", "{\n  \"customer_id\": \"x\"\n}\n");
        write("src/main/resources/b.yml", "charge:\n  customer_id: abc\n");
        write("src/main/resources/c.properties", "customer_id=7\n");
        write("src/main/resources/d.json", "{\"other\": 1}");
        List<TextIndex.Match> matches = TextIndex.build(root).findKey("customer_id");
        assertThat(matches)
                .extracting(TextIndex.Match::file)
                .containsExactlyInAnyOrder(
                        "src/test/resources/a.json",
                        "src/main/resources/b.yml",
                        "src/main/resources/c.properties");
        assertThat(matches).filteredOn(TextIndex.Match::test).hasSize(1);
        assertThat(matches.get(0).line()).isPositive();
    }

    @Test
    void findsQuotedAndYamlValues() throws IOException {
        write("fixtures/x.json", "{\"status\": \"failed\"}");
        write("fixtures/y.yml", "status: failed\n");
        write("fixtures/z.json", "{\"status\": \"failed-over\"}");
        assertThat(TextIndex.build(root).findValue("failed"))
                .extracting(TextIndex.Match::file)
                .containsExactlyInAnyOrder("fixtures/x.json", "fixtures/y.yml");
    }

    @Test
    void ignoresJavaSourcesAndBuildOutput() throws IOException {
        write("src/main/java/A.java", "class A { String s = \"customer_id\"; }");
        write("target/classes/x.json", "{\"customer_id\": 1}");
        assertThat(TextIndex.build(root).findKey("customer_id")).isEmpty();
    }

    @Test
    void emptyIndexFindsNothing() {
        assertThat(TextIndex.empty().findKey("x")).isEmpty();
        assertThat(TextIndex.empty().findValue("x")).isEmpty();
    }

    @Test
    void longLinesAreTruncatedInSnippets() throws IOException {
        write("big.json", "{\"customer_id\": \"" + "x".repeat(1000) + "\"}");
        assertThat(TextIndex.build(root).findKey("customer_id").get(0).snippet()).hasSize(200);
    }
}
