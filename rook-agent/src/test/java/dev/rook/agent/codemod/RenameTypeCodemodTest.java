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

class RenameTypeCodemodTest {

    @TempDir Path temp;

    private CodemodFixtures fixtures;

    @BeforeEach
    void setUp() throws IOException {
        fixtures = new CodemodFixtures(temp);
    }

    private static List<ImpactLocation> in(String... files) {
        return java.util.Arrays.stream(files)
                .map(f -> CodemodFixtures.location(f, 1, 1, "TYPE_USE"))
                .toList();
    }

    private CodemodRunner.Outcome run(Codemod codemod, String... files) {
        Workspace workspace = fixtures.workspace();
        return CodemodRunner.run(workspace, codemod, in(files));
    }

    @Test
    void anInPackageRenameUpdatesImportsTypesConstructorsStaticCallsAndAnnotations()
            throws IOException {
        fixtures.write(
                "src/main/java/com/shop/Service.java",
                """
                package com.shop;

                import com.acme.pay.PaymentClient;
                import com.acme.pay.PaymentClient.Options;
                import java.util.List;

                @PaymentClient.Marker
                class Service extends PaymentClient {
                    private final PaymentClient client = new PaymentClient();
                    List<PaymentClient> all;
                    com.acme.pay.PaymentClient direct;

                    void go() {
                        PaymentClient.create();
                        String text = "PaymentClient stays in strings";
                    }
                }
                """);
        CodemodRunner.Outcome outcome =
                run(
                        new RenameTypeCodemod(
                                "com.acme.pay.PaymentClient", "com.acme.pay.BillingClient"),
                        "src/main/java/com/shop/Service.java");
        String updated = fixtures.current("src/main/java/com/shop/Service.java");
        assertThat(outcome.files()).isEqualTo(1);
        assertThat(updated)
                .contains("import com.acme.pay.BillingClient;")
                .contains("import com.acme.pay.BillingClient.Options;")
                .contains("class Service extends BillingClient")
                .contains("new BillingClient()")
                .contains("List<BillingClient> all;")
                .contains("com.acme.pay.BillingClient direct;")
                .contains("BillingClient.create();")
                .contains("\"PaymentClient stays in strings\"")
                .doesNotContain("import com.acme.pay.PaymentClient");
    }

    @Test
    void movingAPackageKeepingTheNameOnlyChangesTheImport() throws IOException {
        fixtures.write(
                "src/main/java/com/shop/Service.java",
                "package com.shop;\n\nimport com.acme.pay.Money;\n\nclass Service {\n    Money m;\n}\n");
        run(
                new RenameTypeCodemod("com.acme.pay.Money", "com.acme.core.Money"),
                "src/main/java/com/shop/Service.java");
        assertThat(fixtures.current("src/main/java/com/shop/Service.java"))
                .isEqualTo(
                        "package com.shop;\n\nimport com.acme.core.Money;\n\nclass Service {\n    Money m;\n}\n");
    }

    @Test
    void aSamePackageUserGetsAnImportWhenTheTypeMovesAway() throws IOException {
        fixtures.write(
                "src/main/java/com/acme/pay/Gateway.java",
                "package com.acme.pay;\n\nclass Gateway {\n    Money m;\n}\n");
        run(
                new RenameTypeCodemod("com.acme.pay.Money", "com.acme.core.Money"),
                "src/main/java/com/acme/pay/Gateway.java");
        assertThat(fixtures.current("src/main/java/com/acme/pay/Gateway.java"))
                .contains("import com.acme.core.Money;")
                .contains("Money m;");
    }

    @Test
    void aWildcardImporterGetsAnImportForTheNewLocation() throws IOException {
        fixtures.write(
                "src/main/java/com/shop/Service.java",
                "package com.shop;\n\nimport com.acme.pay.*;\n\nclass Service {\n    Money m;\n}\n");
        run(
                new RenameTypeCodemod("com.acme.pay.Money", "com.acme.core.Money"),
                "src/main/java/com/shop/Service.java");
        assertThat(fixtures.current("src/main/java/com/shop/Service.java"))
                .contains("import com.acme.core.Money;")
                .contains("import com.acme.pay.*;");
    }

    @Test
    void aFileThatImportsAnotherTypeWithTheOldSimpleNameIsLeftAlone() throws IOException {
        fixtures.write(
                "src/main/java/com/shop/Service.java",
                "package com.shop;\n\nimport com.acme.pay.Client;\nimport com.other.Client2;\n\nclass Service {\n    Client c;\n}\n");
        fixtures.write(
                "src/main/java/com/shop/Clash.java",
                "package com.shop;\n\nimport com.other.Client;\n\nclass Clash {\n    Client c;\n}\n");
        CodemodRunner.Outcome outcome =
                run(
                        new RenameTypeCodemod("com.acme.pay.Client", "com.acme.pay.BillingClient"),
                        "src/main/java/com/shop/Clash.java");
        assertThat(outcome.files()).isZero();
        assertThat(outcome.skipped())
                .singleElement()
                .asString()
                .contains("another type named Client");
        assertThat(fixtures.current("src/main/java/com/shop/Clash.java"))
                .contains("import com.other.Client;");
    }

    @Test
    void aVariableWithTheTypeNameStopsSimpleNameRewrites() throws IOException {
        fixtures.write(
                "src/main/java/com/shop/Service.java",
                "package com.shop;\n\nimport com.acme.pay.Client;\n\nclass Service {\n    void go(Object Client) {\n        Client.toString();\n    }\n}\n");
        run(
                new RenameTypeCodemod("com.acme.pay.Client", "com.acme.pay.BillingClient"),
                "src/main/java/com/shop/Service.java");
        assertThat(fixtures.current("src/main/java/com/shop/Service.java"))
                .contains("import com.acme.pay.BillingClient;")
                .contains("Client.toString();")
                .doesNotContain("BillingClient.toString();");
    }

    @Test
    void staticImportsKeepTheirMemberName() throws IOException {
        fixtures.write(
                "src/main/java/com/shop/Service.java",
                "package com.shop;\n\nimport static com.acme.pay.Money.ZERO;\nimport static com.acme.pay.Money.*;\n\nclass Service {}\n");
        run(
                new RenameTypeCodemod("com.acme.pay.Money", "com.acme.core.Cash"),
                "src/main/java/com/shop/Service.java");
        assertThat(fixtures.current("src/main/java/com/shop/Service.java"))
                .contains("import static com.acme.core.Cash.ZERO;")
                .contains("import static com.acme.core.Cash.*;");
    }

    @Test
    void filesNotNamedByTheAnalysisAreNotTouched() throws IOException {
        fixtures.write(
                "src/main/java/com/shop/A.java",
                "package com.shop;\n\nimport com.acme.pay.Money;\n\nclass A {\n    Money m;\n}\n");
        fixtures.write(
                "src/main/java/com/shop/B.java",
                "package com.shop;\n\nimport com.acme.pay.Money;\n\nclass B {\n    Money m;\n}\n");
        CodemodRunner.Outcome outcome =
                run(
                        new RenameTypeCodemod("com.acme.pay.Money", "com.acme.core.Money"),
                        "src/main/java/com/shop/A.java");
        assertThat(outcome.files()).isEqualTo(1);
        assertThat(fixtures.current("src/main/java/com/shop/B.java"))
                .contains("import com.acme.pay.Money;");
    }

    @Test
    void filesWithoutAnythingToChangeProduceNoRewrite() throws IOException {
        fixtures.write("src/main/java/com/shop/A.java", "package com.shop;\n\nclass A {}\n");
        CodemodRunner.Outcome outcome =
                run(
                        new RenameTypeCodemod("com.acme.pay.Money", "com.acme.core.Money"),
                        "src/main/java/com/shop/A.java");
        assertThat(outcome.files()).isZero();
        assertThat(outcome.edits()).isZero();
    }

    @Test
    void movingIntoTheDefaultPackageDropsTheQualifier() throws IOException {
        fixtures.write(
                "src/main/java/com/shop/A.java",
                "package com.shop;\n\nclass A {\n    com.acme.pay.Money m;\n}\n");
        run(new RenameTypeCodemod("com.acme.pay.Money", "Money"), "src/main/java/com/shop/A.java");
        assertThat(fixtures.current("src/main/java/com/shop/A.java")).contains("    Money m;");
    }

    @Test
    void identicalOrInvalidRenamesAreRefused() {
        assertThatThrownBy(() -> new RenameTypeCodemod("a.B", "a.B"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenameTypeCodemod("a.B", "a.1x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new RenameTypeCodemod("a.B", "a.C").name()).isEqualTo("rename-type a.B -> a.C");
    }
}
