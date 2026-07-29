package dev.rook.core.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class ChangeEventTest {

    private static final SymbolRef SUBJECT = SymbolRef.operation("POST", "/v1/charges");

    private static ChangeEvent event(Map<String, String> attributes) {
        return ChangeEvent.of(
                "acme-api",
                "1.0.0",
                "2.0.0",
                ChangeKind.OPERATION_REMOVED,
                SUBJECT,
                attributes,
                "removed");
    }

    @Test
    void fingerprintIgnoresAttributeInsertionOrder() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("a", "1");
        first.put("b", "2");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("b", "2");
        second.put("a", "1");
        assertThat(event(first).fingerprint()).isEqualTo(event(second).fingerprint());
    }

    @Test
    void fingerprintDependsOnVersions() {
        ChangeEvent a =
                ChangeEvent.of("s", "1", "2", ChangeKind.METHOD_REMOVED, SUBJECT, Map.of(), "x");
        ChangeEvent b =
                ChangeEvent.of("s", "1", "3", ChangeKind.METHOD_REMOVED, SUBJECT, Map.of(), "x");
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }

    @Test
    void severityDefaultsFromKind() {
        assertThat(event(Map.of()).severity()).isEqualTo(Severity.BREAKING);
        ChangeEvent deprecated =
                ChangeEvent.of(
                        "s", "1", "2", ChangeKind.OPERATION_DEPRECATED, SUBJECT, Map.of(), "d");
        assertThat(deprecated.severity()).isEqualTo(Severity.DEPRECATION);
        assertThat(deprecated.isBreaking()).isFalse();
    }

    @Test
    void severityCanBeOverridden() {
        ChangeEvent e =
                ChangeEvent.of(
                        "s",
                        "1",
                        "2",
                        ChangeKind.ADVISORY_NOTE,
                        Severity.DEPRECATION,
                        SymbolRef.advisory("note"),
                        Map.of(),
                        "n");
        assertThat(e.severity()).isEqualTo(Severity.DEPRECATION);
    }

    @Test
    void attributesAreImmutableCopies() {
        Map<String, String> mutable = new LinkedHashMap<>(Map.of("k", "v"));
        ChangeEvent e = event(mutable);
        mutable.put("other", "x");
        assertThat(e.attributes()).containsOnlyKeys("k");
        assertThatThrownBy(() -> e.attributes().put("z", "z"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullAttributesBecomeEmpty() {
        ChangeEvent e =
                ChangeEvent.of("s", "1", "2", ChangeKind.METHOD_ADDED, SUBJECT, null, "added");
        assertThat(e.attributes()).isEmpty();
        assertThat(e.attribute("missing")).isNull();
    }

    @Property
    void differentAttributesGiveDifferentFingerprints(
            @ForAll("attributeMaps") Map<String, String> left,
            @ForAll("attributeMaps") Map<String, String> right) {
        Assume.that(!left.equals(right));
        assertThat(event(left).fingerprint()).isNotEqualTo(event(right).fingerprint());
    }

    @Property
    void sameAttributesGiveSameFingerprint(
            @ForAll("attributeMaps") Map<String, String> attributes) {
        assertThat(event(attributes).fingerprint()).isEqualTo(event(attributes).fingerprint());
    }

    @Provide
    Arbitrary<Map<String, String>> attributeMaps() {
        Arbitrary<String> keys = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6);
        Arbitrary<String> values = Arbitraries.strings().alpha().numeric().ofMaxLength(8);
        return Arbitraries.maps(keys, values).ofMaxSize(4);
    }
}
