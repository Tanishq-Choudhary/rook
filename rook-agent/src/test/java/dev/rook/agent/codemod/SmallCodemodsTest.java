package dev.rook.agent.codemod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.ImpactLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmallCodemodsTest {

    private static final String FILE = "src/main/java/com/shop/Service.java";

    @TempDir Path temp;

    private CodemodFixtures fixtures;

    @BeforeEach
    void setUp() throws IOException {
        fixtures = new CodemodFixtures(temp);
    }

    private ImpactLocation at(String content, String token, String kind) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int column = lines[i].indexOf(token);
            if (column >= 0) {
                return CodemodFixtures.location(FILE, i + 1, column + 1, kind);
            }
        }
        throw new AssertionError("token not found: " + token);
    }

    private CodemodRunner.Outcome run(Codemod codemod, String content, ImpactLocation... locations)
            throws IOException {
        fixtures.write(FILE, content);
        Workspace workspace = fixtures.workspace();
        return CodemodRunner.run(workspace, codemod, List.of(locations));
    }

    @Test
    void fieldAccessesAndSwitchLabelsAreRenamed() throws IOException {
        String source =
                "package com.shop;\n\nclass Service {\n    void go(Color c) {\n        Color x = Palette.RED;\n        switch (c) {\n            case RED:\n                break;\n            default:\n                break;\n        }\n    }\n}\n";
        CodemodRunner.Outcome outcome =
                run(
                        new RenameFieldCodemod("RED", "CRIMSON"),
                        source,
                        at(source, "Palette.RED", "FIELD_ACCESS"),
                        at(source, "RED:", "SWITCH_LABEL"));
        assertThat(outcome.edits()).isEqualTo(2);
        assertThat(outcome.handled()).hasSize(2);
        assertThat(fixtures.current(FILE))
                .contains("Color x = Palette.CRIMSON;")
                .contains("case CRIMSON:");
    }

    @Test
    void aFieldLocationThatMatchesNothingIsSkipped() throws IOException {
        String source = "package com.shop;\n\nclass Service {\n    int a;\n}\n";
        CodemodRunner.Outcome outcome =
                run(
                        new RenameFieldCodemod("RED", "CRIMSON"),
                        source,
                        CodemodFixtures.location(FILE, 4, 5, "FIELD_ACCESS"));
        assertThat(outcome.edits()).isZero();
        assertThat(outcome.skipped()).singleElement().asString().contains("no use of RED");
    }

    @Test
    void invalidFieldRenamesAreRefused() {
        assertThatThrownBy(() -> new RenameFieldCodemod("a", "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenameFieldCodemod("a", "b-c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new RenameFieldCodemod("a", "b").name()).isEqualTo("rename-field a -> b");
    }

    private static final String CALLS =
            "package com.shop;\n\nclass Service {\n    void go(Svc s) {\n        s.charge(5, \"usd\", true);\n    }\n}\n";

    @Test
    void aMiddleArgumentIsDroppedWithItsComma() throws IOException {
        run(new DropArgumentCodemod("charge", 1), CALLS, at(CALLS, "s.charge", "METHOD_CALL"));
        assertThat(fixtures.current(FILE)).contains("s.charge(5, true);");
    }

    @Test
    void theFirstArgumentIsDroppedWithItsComma() throws IOException {
        run(new DropArgumentCodemod("charge", 0), CALLS, at(CALLS, "s.charge", "METHOD_CALL"));
        assertThat(fixtures.current(FILE)).contains("s.charge(\"usd\", true);");
    }

    @Test
    void theLastArgumentIsDroppedWithTheCommaBeforeIt() throws IOException {
        run(new DropArgumentCodemod("charge", 2), CALLS, at(CALLS, "s.charge", "METHOD_CALL"));
        assertThat(fixtures.current(FILE)).contains("s.charge(5, \"usd\");");
    }

    @Test
    void aSoleArgumentIsDropped() throws IOException {
        String source =
                "package com.shop;\n\nclass Service {\n    void go(Svc s) {\n        s.close(1);\n    }\n}\n";
        run(new DropArgumentCodemod("close", 0), source, at(source, "s.close", "METHOD_CALL"));
        assertThat(fixtures.current(FILE)).contains("s.close();");
    }

    @Test
    void anArgumentWithSideEffectsIsNeverDropped() throws IOException {
        String source =
                "package com.shop;\n\nclass Service {\n    void go(Svc s) {\n        s.charge(next(), 2);\n    }\n}\n";
        CodemodRunner.Outcome outcome =
                run(
                        new DropArgumentCodemod("charge", 0),
                        source,
                        at(source, "s.charge", "METHOD_CALL"));
        assertThat(outcome.edits()).isZero();
        assertThat(outcome.skipped()).singleElement().asString().contains("side effects");
        assertThat(fixtures.current(FILE)).contains("s.charge(next(), 2);");
    }

    @Test
    void callsWithTooFewArgumentsAreLeftAlone() throws IOException {
        String source =
                "package com.shop;\n\nclass Service {\n    void go(Svc s) {\n        s.charge(1);\n    }\n}\n";
        CodemodRunner.Outcome outcome =
                run(
                        new DropArgumentCodemod("charge", 3),
                        source,
                        at(source, "s.charge", "METHOD_CALL"));
        assertThat(outcome.edits()).isZero();
        assertThat(outcome.skipped()).isEmpty();
    }

    @Test
    void pureArgumentShapesAreAccepted() throws IOException {
        String source =
                "package com.shop;\n\nclass Service {\n    int n;\n    void go(Svc s) {\n        s.a(this.n, 1);\n        s.a(-1, 1);\n        s.a(this, 1);\n        s.a(\"x\", 1);\n    }\n}\n";
        run(
                new DropArgumentCodemod("a", 0),
                source,
                CodemodFixtures.location(FILE, 6, 9, "METHOD_CALL"),
                CodemodFixtures.location(FILE, 7, 9, "METHOD_CALL"),
                CodemodFixtures.location(FILE, 8, 9, "METHOD_CALL"),
                CodemodFixtures.location(FILE, 9, 9, "METHOD_CALL"));
        assertThat(fixtures.current(FILE))
                .doesNotContain("this.n")
                .doesNotContain("-1")
                .doesNotContain("\"x\"");
    }

    @Test
    void invalidArgumentRemovalsAreRefused() {
        assertThatThrownBy(() -> new DropArgumentCodemod("a", -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DropArgumentCodemod("1a", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DropArgumentCodemod("a", 2).name()).isEqualTo("drop-argument a#2");
    }

    @Test
    void stringLiteralsAreReplacedAndAnnotationValuesToo() throws IOException {
        String source =
                "package com.shop;\n\nclass Service {\n    @Json(\"user_name\")\n    String name;\n    String path = \"user_name\";\n    String other = \"user_names\";\n}\n";
        CodemodRunner.Outcome outcome =
                run(
                        new ReplaceStringLiteralCodemod("user_name", "username"),
                        source,
                        at(source, "@Json", "ANNOTATION"),
                        at(source, "\"user_name\";", "STRING_LITERAL"));
        assertThat(outcome.edits()).isEqualTo(2);
        assertThat(fixtures.current(FILE))
                .contains("@Json(\"username\")")
                .contains("String path = \"username\";")
                .contains("\"user_names\"");
    }

    @Test
    void replacementsAreEscapedWhenTheyAreQuoted() {
        assertThat(ReplaceStringLiteralCodemod.quote("a\"b\\c\nd\te\rf"))
                .isEqualTo("\"a\\\"b\\\\c\\nd\\te\\rf\"");
    }

    @Test
    void aLocationWithoutTheLiteralIsSkipped() throws IOException {
        String source = "package com.shop;\n\nclass Service {\n    String a = \"other\";\n}\n";
        CodemodRunner.Outcome outcome =
                run(
                        new ReplaceStringLiteralCodemod("user_name", "username"),
                        source,
                        CodemodFixtures.location(FILE, 4, 16, "STRING_LITERAL"));
        assertThat(outcome.edits()).isZero();
        assertThat(outcome.skipped()).singleElement().asString().contains("no literal");
    }

    @Test
    void invalidLiteralReplacementsAreRefused() {
        assertThatThrownBy(() -> new ReplaceStringLiteralCodemod("", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplaceStringLiteralCodemod("x", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReplaceStringLiteralCodemod("a", "b").name()).contains("a").contains("b");
    }
}
