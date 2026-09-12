package dev.rook.eval.generate;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

final class Recipes {

    record Recipe(String slug, BiFunction<Domain, Style, Blueprint> build) {}

    private Recipes() {}

    static List<Recipe> all() {
        return List.of(
                new Recipe("method-rename", Recipes::methodRename),
                new Recipe("class-move", Recipes::classMove),
                new Recipe("argument-removed", Recipes::argumentRemoved),
                new Recipe("parameter-type-changed", Recipes::parameterTypeChanged),
                new Recipe("return-type-changed", Recipes::returnTypeChanged),
                new Recipe("deprecation-only", Recipes::deprecationOnly),
                new Recipe("unrelated-update", Recipes::unrelatedUpdate),
                new Recipe("name-collision", Recipes::nameCollision),
                new Recipe("string-mention", Recipes::stringMention));
    }

    private static String client(Domain d, String pkg, String body) {
        return "package " + pkg + ";\n\npublic class " + d.client() + " {\n\n" + body + "}\n";
    }

    private static String method(String signature, String result) {
        return "    public " + signature + " {\n        return " + result + ";\n    }\n\n";
    }

    private static String status() {
        return method("String status()", "\"up\"");
    }

    private static String tagged(Domain d, String key) {
        return "\"" + d.tag() + ":\" + " + key;
    }

    private static Map<String, String> single(Domain d, String body) {
        return Map.of(d.path(d.client()), client(d, d.pkg(), body));
    }

    private static Usage plain(Domain d, String name) {
        return Usage.of(d.qualifiedClient(), "{c}." + name + "({k})");
    }

    private static Blueprint methodRename(Domain d, Style s) {
        return new Blueprint(
                "method-rename",
                d.client() + "." + d.method() + " is renamed to " + d.renamed(),
                "method-rename",
                "easy",
                false,
                List.of("METHOD_RENAMED"),
                single(
                        d,
                        method("String " + d.method() + "(String key)", tagged(d, "key"))
                                + status()),
                single(
                        d,
                        method("String " + d.renamed() + "(String key)", tagged(d, "key"))
                                + status()),
                plain(d, d.method()),
                plain(d, d.renamed()),
                Map.of(),
                List.of(),
                "." + d.method() + "(");
    }

    private static Blueprint classMove(Domain d, Style s) {
        String movedPkg = d.pkg() + ".client";
        return new Blueprint(
                "class-move",
                d.client() + " moves to " + movedPkg,
                "class-move",
                "easy",
                false,
                List.of("CLASS_MOVED"),
                single(
                        d,
                        method("String " + d.method() + "(String key)", tagged(d, "key"))
                                + status()),
                Map.of(
                        movedPkg.replace('.', '/') + "/" + d.client() + ".java",
                        client(
                                d,
                                movedPkg,
                                method("String " + d.method() + "(String key)", tagged(d, "key"))
                                        + status())),
                plain(d, d.method()),
                Usage.of(movedPkg + "." + d.client(), "{c}." + d.method() + "({k})"),
                Map.of(),
                List.of(),
                d.client());
    }

    private static Blueprint argumentRemoved(Domain d, Style s) {
        return new Blueprint(
                "argument-removed",
                d.client() + "." + d.method() + " no longer takes the audit flag",
                "argument-removed",
                "medium",
                false,
                List.of("METHOD_PARAMETERS_CHANGED"),
                single(
                        d,
                        method(
                                        "String " + d.method() + "(String key, boolean audit)",
                                        tagged(d, "key"))
                                + status()),
                single(
                        d,
                        method("String " + d.method() + "(String key)", tagged(d, "key"))
                                + status()),
                Usage.of(d.qualifiedClient(), "{c}." + d.method() + "({k}, true)"),
                plain(d, d.method()),
                Map.of(),
                List.of(),
                "." + d.method() + "(");
    }

    private static Blueprint parameterTypeChanged(Domain d, Style s) {
        String ref = "package " + d.pkg() + ";\n\npublic record Ref(String key) {}\n";
        String refPath = d.path("Ref");
        return new Blueprint(
                "parameter-type-changed",
                d.client() + "." + d.method() + " takes a Ref instead of a String",
                "parameter-type-changed",
                "hard",
                false,
                List.of("METHOD_PARAMETERS_CHANGED"),
                single(
                        d,
                        method("String " + d.method() + "(String key)", tagged(d, "key"))
                                + status()),
                Map.of(
                        d.path(d.client()),
                        client(
                                d,
                                d.pkg(),
                                method("String " + d.method() + "(Ref ref)", tagged(d, "ref.key()"))
                                        + status()),
                        refPath,
                        ref),
                plain(d, d.method()),
                Usage.of(d.qualifiedClient(), "{c}." + d.method() + "(new Ref({k}))")
                        .withExtraImport(d.pkg() + ".Ref"),
                Map.of(),
                List.of(),
                "." + d.method() + "(");
    }

    private static Blueprint returnTypeChanged(Domain d, Style s) {
        String receipt = "package " + d.pkg() + ";\n\npublic record Receipt(String id) {}\n";
        return new Blueprint(
                "return-type-changed",
                d.client() + "." + d.method() + " returns a Receipt",
                "return-type-changed",
                "hard",
                false,
                List.of("METHOD_RETURN_TYPE_CHANGED"),
                single(
                        d,
                        method("String " + d.method() + "(String key)", tagged(d, "key"))
                                + status()),
                Map.of(
                        d.path(d.client()),
                        client(
                                d,
                                d.pkg(),
                                method(
                                                "Receipt " + d.method() + "(String key)",
                                                "new Receipt(" + tagged(d, "key") + ")")
                                        + status()),
                        d.path("Receipt"),
                        receipt),
                plain(d, d.method()),
                Usage.of(d.qualifiedClient(), "{c}." + d.method() + "({k}).id()"),
                Map.of(),
                List.of(),
                "." + d.method() + "(");
    }

    private static Blueprint deprecationOnly(Domain d, Style s) {
        String deprecated =
                "    @Deprecated(forRemoval = true)\n"
                        + method("String " + d.method() + "(String key)", tagged(d, "key"));
        return new Blueprint(
                "deprecation-only",
                d.client() + "." + d.method() + " is deprecated for removal",
                "deprecation-only",
                "easy",
                true,
                List.of("METHOD_DEPRECATED"),
                single(
                        d,
                        method("String " + d.method() + "(String key)", tagged(d, "key"))
                                + status()),
                single(d, deprecated + status()),
                plain(d, d.method()),
                plain(d, d.method()),
                Map.of(),
                List.of(),
                "." + d.method() + "(");
    }

    private static Blueprint unrelatedUpdate(Domain d, Style s) {
        String body = method("String " + d.method() + "(String key)", tagged(d, "key")) + status();
        return new Blueprint(
                "unrelated-update",
                d.client() + " gains " + d.extra(),
                "unrelated-update",
                "easy",
                true,
                List.of("METHOD_ADDED"),
                single(d, body),
                single(d, body + method("String " + d.extra() + "()", "\"none\"")),
                plain(d, d.method()),
                plain(d, d.method()),
                Map.of(),
                List.of(),
                null);
    }

    private static Blueprint nameCollision(Domain d, Style s) {
        Blueprint rename = methodRename(d, s);
        String ledger =
                "package app;\n\npublic class Ledger {\n\n    public String "
                        + d.method()
                        + "(String key) {\n        return \"ledger:\" + key;\n    }\n}\n";
        String report =
                "package app;\n\npublic class Report {\n\n    public String line(Ledger ledger, String key) {\n        return ledger."
                        + d.method()
                        + "(key);\n    }\n}\n";
        String test =
                "package app;\n\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n\nimport org.junit.jupiter.api.Test;\n\nclass LedgerTest {\n\n    @Test\n    void ledgerLinesKeepTheirOwnPrefix() {\n        assertEquals(\"ledger:x\", new Report().line(new Ledger(), \"x\"));\n    }\n}\n";
        return new Blueprint(
                "name-collision",
                d.client() + "." + d.method() + " is renamed next to a local " + d.method(),
                "name-collision",
                "medium",
                false,
                rename.changeKinds(),
                rename.sdkBefore(),
                rename.sdkAfter(),
                rename.before(),
                rename.gold(),
                Map.of(
                        CustomerCode.MAIN + "Ledger.java",
                        ledger,
                        CustomerCode.MAIN + "Report.java",
                        report,
                        CustomerCode.TEST + "LedgerTest.java",
                        test),
                List.of(CustomerCode.MAIN + "Ledger.java", CustomerCode.MAIN + "Report.java"),
                rename.needle());
    }

    private static Blueprint stringMention(Domain d, Style s) {
        Blueprint rename = methodRename(d, s);
        String audit =
                "package app;\n\npublic class Audit {\n\n    public static final String EVENT = \""
                        + d.method()
                        + "\";\n\n    public String describe() {\n        return \"uses "
                        + d.method()
                        + " on "
                        + d.client()
                        + "\";\n    }\n}\n";
        String properties = "event." + d.method() + "=enabled\n";
        String test =
                "package app;\n\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n\nimport org.junit.jupiter.api.Test;\n\nclass AuditTest {\n\n    @Test\n    void descriptionNamesTheCall() {\n        assertEquals(\"uses "
                        + d.method()
                        + " on "
                        + d.client()
                        + "\", new Audit().describe());\n        assertEquals(\""
                        + d.method()
                        + "\", Audit.EVENT);\n    }\n}\n";
        return new Blueprint(
                "string-mention",
                d.client() + "." + d.method() + " is renamed while strings still mention it",
                "string-mention",
                "medium",
                false,
                rename.changeKinds(),
                rename.sdkBefore(),
                rename.sdkAfter(),
                rename.before(),
                rename.gold(),
                Map.of(
                        CustomerCode.MAIN + "Audit.java",
                        audit,
                        "src/main/resources/events.properties",
                        properties,
                        CustomerCode.TEST + "AuditTest.java",
                        test),
                List.of(CustomerCode.MAIN + "Audit.java", "src/main/resources/events.properties"),
                rename.needle());
    }
}
