package dev.rook.agent.codemod;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodemodSelectorTest {

    private static ChangeEvent event(
            ChangeKind kind, SymbolRef subject, Map<String, String> attributes) {
        return ChangeEvent.of("src", "1", "2", kind, subject, attributes, "summary");
    }

    private static Optional<Codemod> select(ChangeEvent event) {
        return CodemodSelector.codemodFor(event);
    }

    @Test
    void aMethodRenameBecomesARenameMethodCodemod() {
        ChangeEvent event =
                event(
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod("a.B", "old", List.of()),
                        Map.of(Attr.NAME, "old", Attr.REPLACEMENT, "fresh"));
        assertThat(select(event))
                .get()
                .extracting(Codemod::name)
                .isEqualTo("rename-method old -> fresh");
    }

    @Test
    void aClassMoveBecomesARenameTypeCodemod() {
        ChangeEvent event =
                event(
                        ChangeKind.CLASS_MOVED,
                        SymbolRef.javaClass("a.Old"),
                        Map.of(Attr.REPLACEMENT, "b.New"));
        assertThat(select(event))
                .get()
                .extracting(Codemod::name)
                .isEqualTo("rename-type a.Old -> b.New");
    }

    @Test
    void aReplacedEnumValueBecomesALiteralReplacement() {
        ChangeEvent event =
                event(
                        ChangeKind.ENUM_VALUE_REMOVED,
                        SymbolRef.enumValue("Pay.status", "pending"),
                        Map.of(Attr.REPLACEMENT, "waiting"));
        assertThat(select(event))
                .get()
                .extracting(Codemod::name)
                .asString()
                .contains("\"pending\"")
                .contains("\"waiting\"");
    }

    @Test
    void aRenamedSchemaFieldReplacesTheLeafName() {
        ChangeEvent event =
                event(
                        ChangeKind.FIELD_RENAMED,
                        SymbolRef.schemaField("Customer", "address.zip"),
                        Map.of(Attr.REPLACEMENT, "address.postal_code"));
        assertThat(select(event))
                .get()
                .extracting(Codemod::name)
                .asString()
                .contains("\"zip\"")
                .contains("\"postal_code\"");
    }

    @Test
    void aSingleRemovedParameterBecomesAnArgumentDrop() {
        ChangeEvent event =
                event(
                        ChangeKind.METHOD_PARAMETERS_CHANGED,
                        SymbolRef.javaMethod(
                                "a.B", "charge", List.of("long", "java.lang.String", "boolean")),
                        Map.of(
                                Attr.NAME, "charge",
                                Attr.OLD_PARAMETERS, "long,java.lang.String,boolean",
                                Attr.NEW_PARAMETERS, "long,boolean"));
        assertThat(select(event))
                .get()
                .extracting(Codemod::name)
                .isEqualTo("drop-argument charge#1");
    }

    @Test
    void anAmbiguousParameterRemovalIsLeftToTheAgent() {
        ChangeEvent event =
                event(
                        ChangeKind.METHOD_PARAMETERS_CHANGED,
                        SymbolRef.javaMethod("a.B", "charge", List.of("long", "long")),
                        Map.of(
                                Attr.NAME,
                                "charge",
                                Attr.OLD_PARAMETERS,
                                "long,long",
                                Attr.NEW_PARAMETERS,
                                "long"));
        assertThat(select(event)).isEmpty();
    }

    @Test
    void parameterChangesThatAreNotASingleRemovalAreLeftToTheAgent() {
        ChangeEvent reordered =
                event(
                        ChangeKind.METHOD_PARAMETERS_CHANGED,
                        SymbolRef.javaMethod("a.B", "m", List.of("int", "long")),
                        Map.of(
                                Attr.NAME,
                                "m",
                                Attr.OLD_PARAMETERS,
                                "int,long",
                                Attr.NEW_PARAMETERS,
                                "long,int"));
        ChangeEvent addedAndRemoved =
                event(
                        ChangeKind.METHOD_PARAMETERS_CHANGED,
                        SymbolRef.javaMethod("a.B", "m", List.of("int")),
                        Map.of(
                                Attr.NAME,
                                "m",
                                Attr.OLD_PARAMETERS,
                                "int",
                                Attr.NEW_PARAMETERS,
                                "long"));
        ChangeEvent noParametersLeft =
                event(
                        ChangeKind.METHOD_PARAMETERS_CHANGED,
                        SymbolRef.javaMethod("a.B", "m", List.of("int")),
                        Map.of(
                                Attr.NAME,
                                "m",
                                Attr.OLD_PARAMETERS,
                                "int",
                                Attr.NEW_PARAMETERS,
                                ""));
        assertThat(select(reordered)).isEmpty();
        assertThat(select(addedAndRemoved)).isEmpty();
        assertThat(select(noParametersLeft))
                .get()
                .extracting(Codemod::name)
                .isEqualTo("drop-argument m#0");
    }

    @Test
    void eventsWithoutTheNeededAttributesOrKindsGetNoCodemod() {
        assertThat(
                        select(
                                event(
                                        ChangeKind.METHOD_RENAMED,
                                        SymbolRef.javaMethod("a.B", "x", List.of()),
                                        Map.of())))
                .isEmpty();
        assertThat(select(event(ChangeKind.CLASS_MOVED, SymbolRef.javaClass("a.B"), Map.of())))
                .isEmpty();
        assertThat(
                        select(
                                event(
                                        ChangeKind.METHOD_REMOVED,
                                        SymbolRef.javaMethod("a.B", "x", List.of()),
                                        Map.of())))
                .isEmpty();
        assertThat(
                        select(
                                event(
                                        ChangeKind.METHOD_RENAMED,
                                        SymbolRef.javaMethod("a.B", "x", List.of()),
                                        Map.of(Attr.NAME, "x", Attr.REPLACEMENT, "x"))))
                .isEmpty();
    }

    @Test
    void onlyImpactsWithLocationsAndAKnownEventAreSelected() {
        ChangeEvent renamed =
                event(
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod("a.B", "old", List.of()),
                        Map.of(Attr.NAME, "old", Attr.REPLACEMENT, "fresh"));
        ChangeEvent removed =
                event(
                        ChangeKind.METHOD_REMOVED,
                        SymbolRef.javaMethod("a.B", "gone", List.of()),
                        Map.of());
        ChangeEvent unseen =
                event(
                        ChangeKind.CLASS_MOVED,
                        SymbolRef.javaClass("a.Old"),
                        Map.of(Attr.REPLACEMENT, "b.New"));
        ImpactLocation location = CodemodFixtures.location("A.java", 1, 1, "METHOD_CALL");
        ImpactReport report =
                ImpactReport.of(
                        List.of(
                                impact(renamed, List.of(location)),
                                impact(removed, List.of(location)),
                                impact(unseen, List.of()),
                                new Impact(
                                        "unknown-fingerprint",
                                        ChangeKind.METHOD_RENAMED,
                                        renamed.severity(),
                                        "x",
                                        "x",
                                        Map.of(),
                                        List.of(location))));
        List<CodemodSelector.Planned> planned =
                CodemodSelector.select(List.of(renamed, removed, unseen), report);
        assertThat(planned).hasSize(1);
        assertThat(planned.get(0).event()).isEqualTo(renamed);
        assertThat(planned.get(0).locations()).containsExactly(location);
    }

    private static Impact impact(ChangeEvent event, List<ImpactLocation> locations) {
        return new Impact(
                event.fingerprint(),
                event.kind(),
                event.severity(),
                event.subject().name(),
                event.summary(),
                event.attributes(),
                locations);
    }
}
