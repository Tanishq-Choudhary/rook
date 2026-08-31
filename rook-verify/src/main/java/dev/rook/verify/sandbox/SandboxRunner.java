package dev.rook.verify.sandbox;

public interface SandboxRunner {

    String kind();

    SandboxResult run(SandboxSpec spec);
}
