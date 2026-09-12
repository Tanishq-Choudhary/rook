package dev.rook.verify.gate;

public record GateResult(String name, boolean passed, String detail) {

    public static GateResult pass(String name) {
        return new GateResult(name, true, "");
    }

    public static GateResult pass(String name, String detail) {
        return new GateResult(name, true, detail);
    }

    public static GateResult fail(String name, String detail) {
        return new GateResult(name, false, detail);
    }
}
