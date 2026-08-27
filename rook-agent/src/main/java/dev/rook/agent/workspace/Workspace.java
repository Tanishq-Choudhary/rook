package dev.rook.agent.workspace;

import dev.rook.agent.patch.EditApplier;
import dev.rook.agent.patch.FileDiff;
import dev.rook.agent.patch.PatchRejected;
import dev.rook.agent.patch.UnifiedDiff;
import dev.rook.codeintel.RepoFiles;
import dev.rook.core.error.RookException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class Workspace {

    public record EditRecord(
            int sequence, String path, int line, int removedChars, int addedChars) {}

    private final Path root;
    private final WorkspacePolicy policy;
    private final Map<String, String> originals = new LinkedHashMap<>();
    private final List<EditRecord> log = new ArrayList<>();

    private Workspace(Path root, WorkspacePolicy policy) {
        this.root = root.toAbsolutePath().normalize();
        this.policy = policy;
    }

    public static Workspace over(Path root, WorkspacePolicy policy) {
        if (!Files.isDirectory(root)) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.NOT_FOUND, "workspace root is not a directory");
        }
        return new Workspace(root, policy);
    }

    public static Workspace copyOf(Path source, Path target, WorkspacePolicy policy) {
        List<RepoFiles.RepoFile> files =
                RepoFiles.walk(
                        source,
                        RepoFiles.Options.defaults().withMaxFileBytes(policy.maxFileBytes()));
        if (files.size() > policy.maxCopiedFiles()) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.LIMIT_REACHED,
                    "repository has "
                            + files.size()
                            + " files, the limit is "
                            + policy.maxCopiedFiles());
        }
        try {
            Files.createDirectories(target);
            for (RepoFiles.RepoFile file : files) {
                Path destination = target.resolve(file.path());
                Files.createDirectories(destination.getParent());
                Files.copy(file.absolute(), destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.IO, "could not prepare the workspace", e);
        }
        return new Workspace(target, policy);
    }

    public Path root() {
        return root;
    }

    public String read(String path) {
        Path file = resolve(path);
        policy.checkRead(relative(file));
        return load(file, path);
    }

    public String readLines(String path, int from, int to) {
        if (from < 1 || to < from) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.LIMIT_REACHED,
                    "line range must satisfy 1 <= start <= end");
        }
        String[] lines = read(path).split("\n", -1);
        int total = lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        StringBuilder out = new StringBuilder();
        for (int i = from; i <= Math.min(to, total); i++) {
            out.append(i).append(": ").append(lines[i - 1]).append('\n');
        }
        return out.toString();
    }

    public List<String> list(String prefix, int limit) {
        String wanted = prefix == null ? "" : WorkspacePolicy.normalize(prefix);
        List<String> paths = new ArrayList<>();
        for (RepoFiles.RepoFile file : RepoFiles.walk(root)) {
            if (file.path().startsWith(wanted)) {
                paths.add(file.path());
                if (paths.size() >= limit) {
                    break;
                }
            }
        }
        return paths;
    }

    public EditRecord edit(String path, String search, String replace) {
        Path file = resolve(path);
        String relative = relative(file);
        policy.checkEdit(relative);
        guardEditBudget(relative);
        String current = load(file, path);
        EditApplier.Applied applied = EditApplier.apply(current, search, replace);
        return commit(file, relative, current, applied.content(), applied.line(), search, replace);
    }

    public EditRecord rewrite(String path, String content) {
        Path file = resolve(path);
        String relative = relative(file);
        policy.checkEdit(relative);
        guardEditBudget(relative);
        String current = load(file, path);
        if (current.equals(content)) {
            throw new PatchRejected(
                    PatchRejected.Reason.NO_CHANGE,
                    "the rewritten file is identical to the original");
        }
        return commit(
                file,
                relative,
                current,
                content,
                firstDifference(current, content),
                current,
                content);
    }

    private void guardEditBudget(String relative) {
        if (log.size() >= policy.maxEdits()) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.LIMIT_REACHED,
                    "the edit limit of " + policy.maxEdits() + " is reached");
        }
        if (!originals.containsKey(relative) && originals.size() >= policy.maxFilesTouched()) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.LIMIT_REACHED,
                    "the limit of " + policy.maxFilesTouched() + " changed files is reached");
        }
    }

    private EditRecord commit(
            Path file,
            String relative,
            String current,
            String updated,
            int line,
            String removed,
            String added) {
        if (updated.getBytes(StandardCharsets.UTF_8).length > policy.maxFileBytes()) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.TOO_LARGE,
                    "the edited file would exceed the size limit");
        }
        String original = originals.getOrDefault(relative, current);
        if (changedLines(relative, original, updated) + otherChangedLines(relative)
                > policy.maxChangedLines()) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.LIMIT_REACHED,
                    "the limit of "
                            + policy.maxChangedLines()
                            + " changed lines would be exceeded");
        }
        store(file, updated);
        originals.putIfAbsent(relative, current);
        EditRecord record =
                new EditRecord(log.size() + 1, relative, line, removed.length(), added.length());
        log.add(record);
        return record;
    }

    private static int firstDifference(String before, String after) {
        int limit = Math.min(before.length(), after.length());
        int at = 0;
        while (at < limit && before.charAt(at) == after.charAt(at)) {
            at++;
        }
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (before.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    public List<EditRecord> log() {
        return List.copyOf(log);
    }

    public int changedFileCount() {
        return (int) originals.keySet().stream().filter(p -> !unchanged(p)).count();
    }

    public List<String> changedPaths() {
        TreeSet<String> paths = new TreeSet<>();
        for (String path : originals.keySet()) {
            if (!unchanged(path)) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    public String diff() {
        StringBuilder out = new StringBuilder();
        for (String path : changedPaths()) {
            out.append(UnifiedDiff.generate(path, originals.get(path), current(path)));
        }
        return out.toString();
    }

    public int changedLines() {
        int total = 0;
        for (String path : changedPaths()) {
            total += changedLines(path, originals.get(path), current(path));
        }
        return total;
    }

    private boolean unchanged(String path) {
        return originals.get(path).equals(current(path));
    }

    private String current(String path) {
        return load(root.resolve(path), path);
    }

    private int otherChangedLines(String except) {
        int total = 0;
        for (String path : changedPaths()) {
            if (!path.equals(except)) {
                total += changedLines(path, originals.get(path), current(path));
            }
        }
        return total;
    }

    private static int changedLines(String path, String before, String after) {
        if (before.equals(after)) {
            return 0;
        }
        List<FileDiff> parsed = UnifiedDiff.parse(UnifiedDiff.generate(path, before, after));
        return parsed.get(0).changedLines();
    }

    private Path resolve(String path) {
        try {
            return RepoFiles.resolveInside(root, path);
        } catch (RookException e) {
            throw new WorkspaceViolation(WorkspaceViolation.Code.OUTSIDE_ROOT, e.getMessage(), e);
        }
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private String load(Path file, String shown) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new WorkspaceViolation(
                        WorkspaceViolation.Code.NOT_FOUND, shown + " does not exist");
            }
            if (Files.size(file) > policy.maxFileBytes()) {
                throw new WorkspaceViolation(
                        WorkspaceViolation.Code.TOO_LARGE,
                        shown + " is larger than " + policy.maxFileBytes() + " bytes");
            }
            byte[] bytes = Files.readAllBytes(file);
            for (byte b : bytes) {
                if (b == 0) {
                    throw new WorkspaceViolation(
                            WorkspaceViolation.Code.NOT_TEXT, shown + " is a binary file");
                }
            }
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.NOT_TEXT, shown + " is not valid UTF-8 text", e);
        } catch (IOException e) {
            throw new WorkspaceViolation(WorkspaceViolation.Code.IO, "cannot read " + shown, e);
        }
    }

    private void store(Path file, String content) {
        Path temp = null;
        try {
            temp = Files.createTempFile(file.getParent(), ".edit-", ".tmp");
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new WorkspaceViolation(WorkspaceViolation.Code.IO, "cannot write the edit", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    temp.toFile().deleteOnExit();
                }
            }
        }
    }

    public Map<String, String> originals() {
        return new TreeMap<>(originals);
    }
}
