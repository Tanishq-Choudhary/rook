package dev.rook.codeintel.impact;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.JavaIndexer;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImpactAccuracyTest {

    record Scenario(
            String name,
            Map<String, String> files,
            List<ChangeEvent> events,
            Set<String> expected) {}

    record Score(String name, int truePositives, int falsePositives, int falseNegatives) {

        double precision() {
            int reported = truePositives + falsePositives;
            return reported == 0 ? 1.0 : (double) truePositives / reported;
        }

        double recall() {
            int relevant = truePositives + falseNegatives;
            return relevant == 0 ? 1.0 : (double) truePositives / relevant;
        }
    }

    @TempDir Path temp;

    private static Map<String, String> files(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static ChangeEvent method(
            ChangeKind kind, String owner, String name, String... params) {
        return ChangeEvent.of(
                "sdk",
                "1",
                "2",
                kind,
                SymbolRef.javaMethod(owner, name, List.of(params)),
                Map.of(),
                kind.name());
    }

    private static ChangeEvent clazz(ChangeKind kind, String name) {
        return ChangeEvent.of(
                "sdk", "1", "2", kind, SymbolRef.javaClass(name), Map.of(), kind.name());
    }

    private static ChangeEvent javaField(String owner, String name) {
        return ChangeEvent.of(
                "sdk",
                "1",
                "2",
                ChangeKind.JAVA_FIELD_REMOVED,
                SymbolRef.javaField(owner, name),
                Map.of(),
                "removed");
    }

    private static ChangeEvent operation(String verb, String path, String id) {
        return ChangeEvent.of(
                "api",
                "1",
                "2",
                ChangeKind.OPERATION_REMOVED,
                SymbolRef.operation(verb, path),
                Map.of(Attr.OPERATION_ID, id),
                "removed");
    }

    private static ChangeEvent schemaField(ChangeKind kind, String schema, String field) {
        return ChangeEvent.of(
                "api",
                "1",
                "2",
                kind,
                SymbolRef.schemaField(schema, field),
                Map.of(Attr.SCHEMA, schema, Attr.FIELD, field, Attr.DIRECTION, "response"),
                kind.name());
    }

    private static ChangeEvent enumValue(String owner, String value) {
        return ChangeEvent.of(
                "api",
                "1",
                "2",
                ChangeKind.ENUM_VALUE_REMOVED,
                SymbolRef.enumValue(owner, value),
                Map.of(Attr.VALUE, value),
                "removed");
    }

    static List<Scenario> scenarios() {
        List<Scenario> all = new ArrayList<>();
        all.add(
                new Scenario(
                        "method rename",
                        files(
                                "src/main/java/a/Billing.java",
                                """
                                package a;
                                import sdk.PayClient;
                                class Billing {
                                    PayClient client;
                                    void run(PayClient other, long n) {
                                        client.charge(n);
                                        other.charge(n);
                                        PayClient local = new PayClient();
                                        local.charge(1);
                                    }
                                }
                                """,
                                "src/main/java/a/Other.java",
                                """
                                package a;
                                class Other {
                                    void run(Gateway g) {
                                        g.charge(1);
                                    }
                                }
                                """,
                                "src/main/java/a/Shadow.java",
                                """
                                package a;
                                class Shadow {
                                    void run() {
                                        long charge = 5;
                                        System.out.println(charge);
                                    }
                                }
                                """),
                        List.of(
                                method(
                                        ChangeKind.METHOD_RENAMED,
                                        "sdk.PayClient",
                                        "charge",
                                        "long")),
                        Set.of("client.charge(n);", "other.charge(n);", "local.charge(1);")));
        all.add(
                new Scenario(
                        "class removal",
                        files(
                                "src/main/java/a/Auth.java",
                                """
                                package a;
                                import sdk.LegacyAuth;
                                class Auth {
                                    LegacyAuth auth = new LegacyAuth();
                                    void use(LegacyAuth other) {}
                                }
                                """,
                                "src/main/java/a/Fine.java",
                                """
                                package a;
                                import sdk.ModernAuth;
                                class Fine {
                                    ModernAuth auth;
                                }
                                """,
                                "src/main/java/a/LegacyAuthTest.java",
                                """
                                package a;
                                class LegacyAuthTest {
                                    void run() {}
                                }
                                """),
                        List.of(clazz(ChangeKind.CLASS_REMOVED, "sdk.LegacyAuth")),
                        Set.of(
                                "IMPORT@",
                                "LegacyAuth auth = new LegacyAuth();",
                                "void use(LegacyAuth other) {}")));
        all.add(
                new Scenario(
                        "constructor arity",
                        files(
                                "src/main/java/a/Make.java",
                                """
                                package a;
                                import sdk.PayClient;
                                class Make {
                                    Object one() {
                                        return new PayClient("k");
                                    }
                                    Object two() {
                                        return new PayClient("k", 5);
                                    }
                                }
                                """),
                        List.of(
                                method(
                                        ChangeKind.METHOD_PARAMETERS_CHANGED,
                                        "sdk.PayClient",
                                        "<init>",
                                        "java.lang.String")),
                        Set.of("return new PayClient(\"k\");")));
        all.add(
                new Scenario(
                        "enum constant",
                        files(
                                "src/main/java/a/Check.java",
                                """
                                package a;
                                import sdk.Status;
                                class Check {
                                    boolean bad(Status s) {
                                        return s == Status.FAILED;
                                    }
                                }
                                """,
                                "src/main/java/a/Fine.java",
                                """
                                package a;
                                import sdk.Status;
                                class Fine {
                                    boolean ok(Status s) {
                                        return s == Status.PENDING;
                                    }
                                }
                                """,
                                "src/main/java/a/Own.java",
                                """
                                package a;
                                class Own {
                                    enum Mode { FAILED }
                                    boolean x(Mode m) {
                                        return m == Mode.FAILED;
                                    }
                                }
                                """),
                        List.of(javaField("sdk.Status", "FAILED")),
                        Set.of("return s == Status.FAILED;")));
        all.add(
                new Scenario(
                        "endpoint removal",
                        files(
                                "src/main/java/a/Api.java",
                                """
                                package a;
                                interface Api {
                                    @GET("/v1/charges/{id}")
                                    Object get(String id);
                                    @GET("/v1/customers/{id}")
                                    Object customer(String id);
                                }
                                """,
                                "src/main/java/a/Raw.java",
                                """
                                package a;
                                class Raw {
                                    String url(String id) {
                                        return "/v1/charges/" + id;
                                    }
                                    String other() {
                                        return "/v1/invoices";
                                    }
                                }
                                """),
                        List.of(operation("GET", "/v1/charges/{id}", "getCharge")),
                        Set.of("@GET(\"/v1/charges/{id}\")")));
        all.add(
                new Scenario(
                        "dto field rename",
                        files(
                                "src/main/java/a/Charge.java",
                                """
                                package a;
                                class Charge {
                                    @JsonProperty("customer_id")
                                    private String customerId;
                                    String getCustomerId() {
                                        return customerId;
                                    }
                                }
                                """,
                                "src/main/java/a/Use.java",
                                """
                                package a;
                                class Use {
                                    String owner(Charge c) {
                                        return c.getCustomerId();
                                    }
                                }
                                """,
                                "src/main/java/a/Unrelated.java",
                                """
                                package a;
                                class Unrelated {
                                    String name(Invoice i) {
                                        return i.getCustomerId();
                                    }
                                }
                                """,
                                "src/test/resources/charge.json",
                                "{ \"customer_id\": \"c1\" }"),
                        List.of(schemaField(ChangeKind.FIELD_RENAMED, "Charge", "customer_id")),
                        Set.of(
                                "DTO_FIELD@a.Charge",
                                "return c.getCustomerId();",
                                "{ \"customer_id\": \"c1\" }")));
        all.add(
                new Scenario(
                        "openapi enum value",
                        files(
                                "src/main/java/a/ChargeStatus.java",
                                """
                                package a;
                                enum ChargeStatus {
                                    PENDING,
                                    FAILED
                                }
                                """,
                                "src/main/java/a/Handle.java",
                                """
                                package a;
                                class Handle {
                                    boolean bad(ChargeStatus s) {
                                        return s == ChargeStatus.FAILED;
                                    }
                                }
                                """),
                        List.of(enumValue("Charge.status", "failed")),
                        Set.of(
                                "ENUM_CONSTANT@a.ChargeStatus",
                                "return s == ChargeStatus.FAILED;")));
        all.add(
                new Scenario(
                        "nothing uses it",
                        files(
                                "src/main/java/a/Quiet.java",
                                """
                                package a;
                                class Quiet {
                                    void run() {}
                                }
                                """),
                        List.of(
                                method(
                                        ChangeKind.METHOD_REMOVED,
                                        "sdk.PayClient",
                                        "charge",
                                        "long")),
                        Set.of()));
        return all;
    }

    private static String label(ImpactLocation location) {
        if (location.referenceKind().equals("RESOURCE")) {
            return location.snippet();
        }
        return location.snippet().isEmpty()
                ? location.referenceKind() + "@" + location.enclosing().replaceAll("#.*", "")
                : location.snippet();
    }

    private Score run(Scenario scenario, int index) throws IOException {
        Path root = Files.createDirectories(temp.resolve("scenario-" + index));
        for (Map.Entry<String, String> file : scenario.files().entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        CodeIndex codeIndex = JavaIndexer.index(root);
        ImpactReport report = ImpactAnalyzer.create(codeIndex, null).analyze(scenario.events());
        Set<String> reported = new HashSet<>();
        for (Impact impact : report.impacts()) {
            for (ImpactLocation location : impact.locations()) {
                if (location.confidence().compareTo(Confidence.HEURISTIC) >= 0) {
                    reported.add(label(location));
                }
            }
        }
        int tp = 0;
        int fp = 0;
        for (String item : reported) {
            if (scenario.expected().contains(item)) {
                tp++;
            } else {
                fp++;
            }
        }
        int fn = (int) scenario.expected().stream().filter(e -> !reported.contains(e)).count();
        return new Score(scenario.name(), tp, fp, fn);
    }

    @Test
    void impactAnalysisMeetsTheAccuracyBarOnLabelledScenarios() throws IOException {
        List<Score> scores = new ArrayList<>();
        int index = 0;
        for (Scenario scenario : scenarios()) {
            scores.add(run(scenario, index++));
        }
        int tp = scores.stream().mapToInt(Score::truePositives).sum();
        int fp = scores.stream().mapToInt(Score::falsePositives).sum();
        int fn = scores.stream().mapToInt(Score::falseNegatives).sum();
        double precision = (double) tp / (tp + fp);
        double recall = (double) tp / (tp + fn);
        for (Score score : scores) {
            System.out.printf(
                    "impact accuracy %-18s tp=%d fp=%d fn=%d precision=%.2f recall=%.2f%n",
                    score.name(),
                    score.truePositives(),
                    score.falsePositives(),
                    score.falseNegatives(),
                    score.precision(),
                    score.recall());
        }
        System.out.printf(
                "impact accuracy overall tp=%d fp=%d fn=%d precision=%.3f recall=%.3f%n",
                tp, fp, fn, precision, recall);
        assertThat(precision).isGreaterThanOrEqualTo(0.9);
        assertThat(recall).isGreaterThanOrEqualTo(0.9);
        scores.forEach(s -> assertThat(s.falsePositives()).as(s.name()).isLessThanOrEqualTo(1));
    }

    @Test
    void negativeScenariosProduceNoReportedLocations() throws IOException {
        Scenario quiet = scenarios().get(scenarios().size() - 1);
        Score score = run(quiet, 99);
        assertThat(score.truePositives()).isZero();
        assertThat(score.falsePositives()).isZero();
    }
}
