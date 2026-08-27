package dev.rook.agent.codemod;

import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspacePolicy;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.ImpactLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class CodemodFixtures {

    final Path repo;
    final Path work;

    CodemodFixtures(Path temp) throws IOException {
        this.repo = Files.createDirectories(temp.resolve("repo"));
        this.work = temp.resolve("work");
    }

    void write(String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    Workspace workspace() {
        return Workspace.copyOf(repo, work, WorkspacePolicy.standard());
    }

    Workspace workspace(WorkspacePolicy policy) {
        return Workspace.copyOf(repo, work, policy);
    }

    String current(String relative) throws IOException {
        return Files.readString(work.resolve(relative));
    }

    static ImpactLocation location(String file, int line, int column, String kind) {
        return location(file, line, column, kind, Confidence.RESOLVED);
    }

    static ImpactLocation location(
            String file, int line, int column, String kind, Confidence confidence) {
        return new ImpactLocation(
                file, line, column, line, "", "enclosing", kind, confidence, "target", false);
    }
}
