package dev.rook.changes.jar;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.Severity;
import dev.rook.core.json.Json;
import dev.rook.testkit.TestJars;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarDiffTest {

    @TempDir Path dir;

    private static Map<String, String> sources(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private List<ChangeEvent> diff(Map<String, String> before, Map<String, String> after) {
        ApiSurface old = ApiSurfaceReader.read(TestJars.build(dir, "before", before));
        ApiSurface now = ApiSurfaceReader.read(TestJars.build(dir, "after", after));
        return ApiDiffer.diff("acme-sdk", "1.0.0", "2.0.0", old, now);
    }

    private static Optional<ChangeEvent> find(
            List<ChangeEvent> events, ChangeKind kind, String subject) {
        return events.stream()
                .filter(e -> e.kind() == kind && e.subject().name().equals(subject))
                .findFirst();
    }

    @Test
    void identicalJarsHaveNoChanges() {
        Map<String, String> src =
                sources(
                        "a.Client",
                        "package a; public class Client { public void run(String s) {} }");
        assertThat(diff(src, src)).isEmpty();
    }

    @Test
    void removedClassIsBreaking() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Client",
                                "package a; public class Client { public void run() {} }",
                                "a.Legacy",
                                "package a; public class Legacy { public void old() {} }"),
                        sources(
                                "a.Client",
                                "package a; public class Client { public void run() {} }"));
        ChangeEvent removed = find(events, ChangeKind.CLASS_REMOVED, "a.Legacy").orElseThrow();
        assertThat(removed.severity()).isEqualTo(Severity.BREAKING);
    }

    @Test
    void classMovedToAnotherPackageKeepsItsMembers() {
        String body =
                "public class Charge { public long amount() { return 0; } public String id() { return \"\"; } }";
        List<ChangeEvent> events =
                diff(
                        sources("a.model.Charge", "package a.model; " + body),
                        sources("a.payments.Charge", "package a.payments; " + body));
        ChangeEvent moved = find(events, ChangeKind.CLASS_MOVED, "a.model.Charge").orElseThrow();
        assertThat(moved.attribute(Attr.REPLACEMENT)).isEqualTo("a.payments.Charge");
        assertThat(find(events, ChangeKind.CLASS_REMOVED, "a.model.Charge")).isEmpty();
    }

    @Test
    void renamedClassInTheSamePackageIsLinkedByMembers() {
        String members =
                "{ public long amount() { return 0; } public String id() { return \"\"; } public void cancel() {} }";
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.PaymentClient",
                                "package a; public class PaymentClient " + members),
                        sources(
                                "a.PaymentsClient",
                                "package a; public class PaymentsClient " + members));
        assertThat(find(events, ChangeKind.CLASS_MOVED, "a.PaymentClient"))
                .get()
                .extracting(e -> e.attribute(Attr.REPLACEMENT))
                .isEqualTo("a.PaymentsClient");
    }

    @Test
    void unrelatedRemovalAndAdditionAreNotLinked() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Foo",
                                "package a; public class Foo { public void one() {} public void two() {} }"),
                        sources("b.Bar", "package b; public class Bar { public void three() {} }"));
        assertThat(find(events, ChangeKind.CLASS_REMOVED, "a.Foo")).isPresent();
    }

    @Test
    void removedMethodIsReportedWithItsSignature() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Client",
                                "package a; public class Client { public void keep() {} public void drop(String a, long b) {} }"),
                        sources(
                                "a.Client",
                                "package a; public class Client { public void keep() {} }"));
        assertThat(find(events, ChangeKind.METHOD_REMOVED, "a.Client#drop(java.lang.String,long)"))
                .isPresent();
        assertThat(events).hasSize(1);
    }

    @Test
    void renamedMethodWithTheSameSignatureIsLinked() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Client",
                                "package a; public class Client { public String charge(long amount) { return \"\"; } }"),
                        sources(
                                "a.Client",
                                "package a; public class Client { public String createCharge(long amount) { return \"\"; } }"));
        ChangeEvent renamed =
                find(events, ChangeKind.METHOD_RENAMED, "a.Client#charge(long)").orElseThrow();
        assertThat(renamed.attribute(Attr.REPLACEMENT)).isEqualTo("createCharge");
        assertThat(find(events, ChangeKind.METHOD_REMOVED, "a.Client#charge(long)")).isEmpty();
        assertThat(find(events, ChangeKind.METHOD_ADDED, "a.Client#createCharge(long)")).isEmpty();
    }

    @Test
    void addedParameterIsASignatureChange() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Client",
                                "package a; public class Client { public void send(String to) {} }"),
                        sources(
                                "a.Client",
                                "package a; public class Client { public void send(String to, int retries) {} }"));
        ChangeEvent changed =
                find(
                                events,
                                ChangeKind.METHOD_PARAMETERS_CHANGED,
                                "a.Client#send(java.lang.String)")
                        .orElseThrow();
        assertThat(changed.attribute(Attr.OLD_PARAMETERS)).isEqualTo("java.lang.String");
        assertThat(changed.attribute(Attr.NEW_PARAMETERS)).isEqualTo("java.lang.String,int");
    }

    @Test
    void returnTypeChangeIsReportedSeparately() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Client",
                                "package a; public class Client { public int count() { return 0; } }"),
                        sources(
                                "a.Client",
                                "package a; public class Client { public long count() { return 0; } }"));
        ChangeEvent changed =
                find(events, ChangeKind.METHOD_RETURN_TYPE_CHANGED, "a.Client#count()")
                        .orElseThrow();
        assertThat(changed.attribute(Attr.FROM)).isEqualTo("int");
        assertThat(changed.attribute(Attr.TO)).isEqualTo("long");
    }

    @Test
    void removingOneOverloadLeavesTheOtherAlone() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Client",
                                "package a; public class Client { public void send(String a) {} public void send(String a, String b, String c, String d, String e) {} }"),
                        sources(
                                "a.Client",
                                "package a; public class Client { public void send(String a) {} }"));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).kind()).isEqualTo(ChangeKind.METHOD_REMOVED);
    }

    @Test
    void methodsMovedUpToASuperclassAreNotReportedAsRemoved() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Base",
                                "package a; public class Base { }",
                                "a.Client",
                                "package a; public class Client extends Base { public void shared() {} }"),
                        sources(
                                "a.Base",
                                "package a; public class Base { public void shared() {} }",
                                "a.Client",
                                "package a; public class Client extends Base { }"));
        assertThat(find(events, ChangeKind.METHOD_REMOVED, "a.Client#shared()")).isEmpty();
    }

    @Test
    void newDeprecationRecordsForRemoval() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Client",
                                "package a; public class Client { public void old() {} public void other() {} }"),
                        sources(
                                "a.Client",
                                "package a; public class Client { @Deprecated(forRemoval = true) public void old() {} @Deprecated public void other() {} }"));
        ChangeEvent old =
                find(events, ChangeKind.METHOD_DEPRECATED, "a.Client#old()").orElseThrow();
        assertThat(old.attribute(Attr.FOR_REMOVAL)).isEqualTo("true");
        ChangeEvent other =
                find(events, ChangeKind.METHOD_DEPRECATED, "a.Client#other()").orElseThrow();
        assertThat(other.attribute(Attr.FOR_REMOVAL)).isEqualTo("false");
        assertThat(old.severity()).isEqualTo(Severity.DEPRECATION);
    }

    @Test
    void deprecatedClassIsReported() {
        List<ChangeEvent> events =
                diff(
                        sources("a.Old", "package a; public class Old { public void x() {} }"),
                        sources(
                                "a.Old",
                                "package a; @Deprecated public class Old { public void x() {} }"));
        assertThat(find(events, ChangeKind.CLASS_DEPRECATED, "a.Old")).isPresent();
    }

    @Test
    void fieldRemovalAndTypeChangeAreReported() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Config",
                                "package a; public class Config { public int timeout; public String name; }"),
                        sources(
                                "a.Config",
                                "package a; public class Config { public long timeout; }"));
        assertThat(find(events, ChangeKind.JAVA_FIELD_REMOVED, "a.Config#name")).isPresent();
        ChangeEvent changed =
                find(events, ChangeKind.JAVA_FIELD_TYPE_CHANGED, "a.Config#timeout").orElseThrow();
        assertThat(changed.attribute(Attr.FROM)).isEqualTo("int");
        assertThat(changed.attribute(Attr.TO)).isEqualTo("long");
    }

    @Test
    void removedEnumConstantIsFlaggedAsAnEnumConstant() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Status",
                                "package a; public enum Status { OPEN, FAILED, CLOSED }"),
                        sources("a.Status", "package a; public enum Status { OPEN, CLOSED }"));
        ChangeEvent removed =
                find(events, ChangeKind.JAVA_FIELD_REMOVED, "a.Status#FAILED").orElseThrow();
        assertThat(removed.attribute(Attr.ENUM_CONSTANT)).isEqualTo("true");
    }

    @Test
    void constructorChangesAreSignatureChanges() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Client",
                                "package a; public class Client { public Client(String key) {} }"),
                        sources(
                                "a.Client",
                                "package a; public class Client { public Client(String key, int timeout) {} }"));
        assertThat(
                        find(
                                events,
                                ChangeKind.METHOD_PARAMETERS_CHANGED,
                                "a.Client#<init>(java.lang.String)"))
                .isPresent();
    }

    @Test
    void nonPublicMembersAreInvisible() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Client",
                                "package a; public class Client { void hidden() {} private void secret() {} public void shown() {} }"),
                        sources(
                                "a.Client",
                                "package a; public class Client { public void shown() {} }"));
        assertThat(events).isEmpty();
    }

    @Test
    void protectedMembersCount() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Client",
                                "package a; public class Client { protected void hook() {} }"),
                        sources("a.Client", "package a; public class Client { }"));
        assertThat(find(events, ChangeKind.METHOD_REMOVED, "a.Client#hook()")).isPresent();
    }

    @Test
    void nestedClassesUseDottedNames() {
        List<ChangeEvent> events =
                diff(
                        sources(
                                "a.Outer",
                                "package a; public class Outer { public static class Inner { public void go() {} } }"),
                        sources(
                                "a.Outer",
                                "package a; public class Outer { public static class Inner { } }"));
        assertThat(find(events, ChangeKind.METHOD_REMOVED, "a.Outer.Inner#go()")).isPresent();
    }

    @Test
    void addedMembersAreAdditive() {
        List<ChangeEvent> events =
                diff(
                        sources("a.Client", "package a; public class Client { }"),
                        sources(
                                "a.Client",
                                "package a; public class Client { public void fresh() {} }"));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).severity()).isEqualTo(Severity.ADDITIVE);
    }

    @Test
    void surfaceSurvivesAJsonRoundTrip() {
        ApiSurface surface =
                ApiSurfaceReader.read(
                        TestJars.build(
                                dir,
                                "rt",
                                sources(
                                        "a.Client",
                                        "package a; public class Client { @Deprecated(forRemoval = true) public void go(String s) {} public int n; }")));
        ApiSurface copy = Json.read(Json.write(surface), ApiSurface.class);
        assertThat(copy).isEqualTo(surface);
    }

    @Test
    void outputIsDeterministic() {
        Map<String, String> before =
                sources(
                        "a.Client",
                        "package a; public class Client { public void one() {} public void two() {} public int f; }");
        Map<String, String> after = sources("a.Client", "package a; public class Client { }");
        assertThat(diff(before, after)).isEqualTo(diff(before, after));
    }
}
