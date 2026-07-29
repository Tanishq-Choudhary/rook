package dev.rook.core.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SymbolRefTest {

    @Test
    void operationNamesUseUpperCaseMethod() {
        assertThat(SymbolRef.operation("post", "/v1/charges").name()).isEqualTo("POST /v1/charges");
    }

    @Test
    void parsesMethodSignatureWithPlainParameters() {
        SymbolRef ref =
                SymbolRef.javaMethod(
                        "com.acme.pay.PaymentClient",
                        "charge",
                        List.of("java.lang.String", "long"));
        SymbolRef.MethodSignature signature = ref.asMethod().orElseThrow();
        assertThat(signature.owner()).isEqualTo("com.acme.pay.PaymentClient");
        assertThat(signature.name()).isEqualTo("charge");
        assertThat(signature.parameterTypes()).containsExactly("java.lang.String", "long");
    }

    @Test
    void parsesMethodSignatureWithGenericParameters() {
        SymbolRef ref =
                SymbolRef.javaMethod(
                        "a.B",
                        "put",
                        List.of("java.util.Map<java.lang.String,java.lang.Object>", "int"));
        assertThat(ref.asMethod().orElseThrow().parameterTypes())
                .containsExactly("java.util.Map<java.lang.String,java.lang.Object>", "int");
    }

    @Test
    void parsesMethodSignatureWithNoParameters() {
        SymbolRef ref = SymbolRef.javaMethod("a.B", "close", List.of());
        assertThat(ref.asMethod().orElseThrow().parameterTypes()).isEmpty();
    }

    @Test
    void nonMethodSymbolsHaveNoMethodSignature() {
        assertThat(SymbolRef.javaClass("a.B").asMethod()).isEmpty();
        assertThat(SymbolRef.operation("GET", "/x").asMethod()).isEmpty();
    }

    @Test
    void malformedMethodNameYieldsNoSignature() {
        assertThat(new SymbolRef(SymbolKind.JAVA_METHOD, "no-hash-here").asMethod()).isEmpty();
        assertThat(new SymbolRef(SymbolKind.JAVA_METHOD, "a.B#broken(").asMethod()).isEmpty();
    }

    @Test
    void ownerTypeIsResolvedForJavaSymbols() {
        assertThat(SymbolRef.javaClass("a.B").ownerType()).contains("a.B");
        assertThat(SymbolRef.javaField("a.B", "limit").ownerType()).contains("a.B");
        assertThat(SymbolRef.javaMethod("a.B", "run", List.of()).ownerType()).contains("a.B");
        assertThat(SymbolRef.schema("Charge").ownerType()).isEmpty();
    }

    @Test
    void schemaFieldAndEnumValueNamesNest() {
        SymbolRef field = SymbolRef.schemaField("Charge", "amount");
        assertThat(field.name()).isEqualTo("Charge.amount");
        assertThat(SymbolRef.enumValue(field.name(), "usd").name()).isEqualTo("Charge.amount=usd");
    }

    @Test
    void blankNamesAreRejected() {
        assertThatThrownBy(() -> new SymbolRef(SymbolKind.SCHEMA, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
