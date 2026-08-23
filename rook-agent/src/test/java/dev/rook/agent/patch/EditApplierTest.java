package dev.rook.agent.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EditApplierTest {

    private static final String FILE =
            "class A {\n    int one() {\n        return 1;\n    }\n\n    int two() {\n        return 2;\n    }\n}\n";

    private static PatchRejected.Reason rejection(String content, String search, String replace) {
        try {
            EditApplier.apply(content, search, replace);
        } catch (PatchRejected e) {
            return e.reason();
        }
        throw new AssertionError("expected a rejection");
    }

    @Test
    void anExactUniqueMatchIsReplacedAndItsLineIsReported() {
        EditApplier.Applied applied =
                EditApplier.apply(FILE, "        return 2;\n", "        return 20;\n");
        assertThat(applied.content()).contains("return 20;").contains("return 1;");
        assertThat(applied.line()).isEqualTo(7);
    }

    @Test
    void aRepeatedSearchIsAmbiguous() {
        assertThat(rejection(FILE, "    }\n", "")).isEqualTo(PatchRejected.Reason.AMBIGUOUS);
    }

    @Test
    void overlappingRepeatsAreStillAmbiguous() {
        assertThat(rejection("aaa", "aa", "b")).isEqualTo(PatchRejected.Reason.AMBIGUOUS);
    }

    @Test
    void anEmptySearchIsRefused() {
        assertThat(rejection(FILE, "", "x")).isEqualTo(PatchRejected.Reason.EMPTY_SEARCH);
    }

    @Test
    void aReplacementIdenticalToTheSearchIsRefused() {
        assertThat(rejection(FILE, "return 1;", "return 1;"))
                .isEqualTo(PatchRejected.Reason.NO_CHANGE);
    }

    @Test
    void missingTextIsReported() {
        assertThat(rejection(FILE, "return 99;", "x")).isEqualTo(PatchRejected.Reason.NOT_FOUND);
    }

    @Test
    void trailingWhitespaceDifferencesAreTolerated() {
        String file = "int a = 1;   \nint b = 2;\t\nint c = 3;\n";
        EditApplier.Applied applied =
                EditApplier.apply(file, "int a = 1;\nint b = 2;\n", "int a = 10;\nint b = 20;\n");
        assertThat(applied.content()).isEqualTo("int a = 10;\nint b = 20;\nint c = 3;\n");
        assertThat(applied.line()).isEqualTo(1);
    }

    @Test
    void crlfFilesAcceptLfSearchesAndKeepTheirLineEndings() {
        String file = "one\r\ntwo\r\nthree\r\n";
        EditApplier.Applied applied = EditApplier.apply(file, "two\n", "two\nextra\n");
        assertThat(applied.content()).isEqualTo("one\r\ntwo\r\nextra\r\nthree\r\n");
    }

    @Test
    void blankLinesAroundTheSearchAreIgnoredInTolerantMode() {
        String file = "a  \nb  \nc\n";
        assertThat(EditApplier.apply(file, "\na\nb\n\n", "x\ny").content()).isEqualTo("x\ny\nc\n");
    }

    @Test
    void tolerantMatchesMustAlsoBeUnique() {
        String file = "x = 1;  \ny = 2;\nx = 1;  \n";
        assertThat(rejection(file, "x = 1;\n", "x = 3;\n"))
                .isEqualTo(PatchRejected.Reason.AMBIGUOUS);
    }

    @Test
    void aBlankSearchIsRefusedEvenWhenItOnlyHasWhitespace() {
        assertThat(rejection("a\n", "  \n", "b")).isEqualTo(PatchRejected.Reason.EMPTY_SEARCH);
    }

    @Test
    void indentationDifferencesAreNotTolerated() {
        assertThatThrownBy(() -> EditApplier.apply(FILE, "return 2;\n  }", "x"))
                .isInstanceOf(PatchRejected.class);
    }

    @Test
    void deletingTextWithAnEmptyReplacementWorks() {
        assertThat(EditApplier.apply("a\nb\nc\n", "b\n", "").content()).isEqualTo("a\nc\n");
    }

    @Test
    void editBlocksRejectNulls() {
        assertThatThrownBy(() -> new EditBlock("p", null, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThat(new EditBlock("p", "a", "b").path()).isEqualTo("p");
    }
}
