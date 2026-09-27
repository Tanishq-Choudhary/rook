package dev.rook.github.git;

import dev.rook.core.error.RookException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

public final class GitCheckout implements AutoCloseable {

    public enum PushStatus {
        PUSHED,
        UP_TO_DATE,
        REJECTED
    }

    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,100}");
    private static final String BOT_NAME = "rook[bot]";
    private static final String BOT_EMAIL = "rook[bot]@users.noreply.github.com";

    private final Git git;
    private final Path directory;
    private final String defaultBranch;
    private final CredentialsProvider credentials;

    private GitCheckout(
            Git git, Path directory, String defaultBranch, CredentialsProvider credentials) {
        this.git = git;
        this.directory = directory;
        this.defaultBranch = defaultBranch;
        this.credentials = credentials;
    }

    public static GitCheckout clone(
            String url, Path directory, String defaultBranch, String token) {
        CredentialsProvider credentials =
                token == null
                        ? null
                        : new UsernamePasswordCredentialsProvider("x-access-token", token);
        try {
            Git git =
                    Git.cloneRepository()
                            .setURI(url)
                            .setDirectory(directory.toFile())
                            .setBranch(defaultBranch)
                            .setCloneAllBranches(false)
                            .setCredentialsProvider(credentials)
                            .call();
            return new GitCheckout(git, directory, defaultBranch, credentials);
        } catch (GitAPIException e) {
            throw RookException.invalid("the repository could not be cloned", e);
        }
    }

    public Path directory() {
        return directory;
    }

    public String headSha() {
        try {
            return git.getRepository().resolve("HEAD").name();
        } catch (IOException e) {
            throw RookException.invalid("the checkout has no head", e);
        }
    }

    public void detachAt(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{40}")) {
            throw RookException.invalid("the commit id is not valid");
        }
        try {
            git.checkout().setName(sha).call();
        } catch (GitAPIException | JGitInternalException e) {
            throw RookException.invalid("the commit could not be checked out", e);
        }
    }

    public Optional<String> remoteBranchSha(String branch) {
        checkBranch(branch);
        try {
            for (Ref ref :
                    git.lsRemote()
                            .setRemote("origin")
                            .setHeads(true)
                            .setCredentialsProvider(credentials)
                            .call()) {
                if (ref.getName().equals("refs/heads/" + branch)) {
                    return Optional.of(ref.getObjectId().name());
                }
            }
            return Optional.empty();
        } catch (GitAPIException e) {
            throw RookException.invalid("the remote branches could not be listed", e);
        }
    }

    public void startBranch(String branch) {
        checkBranch(branch);
        try {
            git.checkout()
                    .setCreateBranch(true)
                    .setName(branch)
                    .setStartPoint("origin/" + defaultBranch)
                    .call();
        } catch (GitAPIException e) {
            throw RookException.invalid("the branch could not be created", e);
        }
    }

    public void write(Map<String, String> files, Collection<String> deletions) {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = resolve(file.getKey());
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw RookException.invalid("a file could not be written: " + file.getKey(), e);
            }
        }
        for (String path : deletions) {
            try {
                Files.deleteIfExists(resolve(path));
            } catch (IOException e) {
                throw RookException.invalid("a file could not be removed: " + path, e);
            }
        }
    }

    public Optional<String> commit(String message, Collection<String> paths) {
        if (paths.isEmpty()) {
            return Optional.empty();
        }
        try {
            for (String path : paths) {
                String pattern = path.replace(File.separatorChar, '/');
                if (Files.exists(resolve(path))) {
                    git.add().addFilepattern(pattern).call();
                } else {
                    git.rm().setCached(true).addFilepattern(pattern).call();
                }
            }
            Status status = git.status().call();
            if (status.getAdded().isEmpty()
                    && status.getChanged().isEmpty()
                    && status.getRemoved().isEmpty()) {
                return Optional.empty();
            }
            PersonIdent bot = new PersonIdent(BOT_NAME, BOT_EMAIL);
            RevCommit commit =
                    git.commit()
                            .setMessage(message)
                            .setAuthor(bot)
                            .setCommitter(bot)
                            .setSign(false)
                            .call();
            return Optional.of(commit.name());
        } catch (GitAPIException e) {
            throw RookException.invalid("the change could not be committed", e);
        }
    }

    public PushStatus push(String branch) {
        checkBranch(branch);
        if (branch.equals(defaultBranch)) {
            throw RookException.forbidden("Rook never pushes to the default branch");
        }
        try {
            Iterable<PushResult> results =
                    git.push()
                            .setRemote("origin")
                            .add("refs/heads/" + branch + ":refs/heads/" + branch)
                            .setCredentialsProvider(credentials)
                            .call();
            PushStatus status = PushStatus.UP_TO_DATE;
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    switch (update.getStatus()) {
                        case OK -> status = PushStatus.PUSHED;
                        case UP_TO_DATE -> {}
                        default -> {
                            return PushStatus.REJECTED;
                        }
                    }
                }
            }
            return status;
        } catch (GitAPIException e) {
            throw RookException.invalid("the branch could not be pushed", e);
        }
    }

    public Repository repository() {
        return git.getRepository();
    }

    static void checkBranch(String branch) {
        if (branch == null
                || !BRANCH.matcher(branch).matches()
                || branch.contains("..")
                || branch.endsWith("/")
                || branch.endsWith(".lock")
                || branch.contains("//")) {
            throw RookException.invalid("the branch name is not allowed");
        }
    }

    private Path resolve(String relative) {
        Path target = directory.resolve(relative.replace('\\', '/')).normalize();
        Path root = directory.toAbsolutePath().normalize();
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.equals(root)) {
            throw RookException.invalid("the path leaves the repository: " + relative);
        }
        Path relativeToRoot = root.relativize(absolute);
        if (relativeToRoot.getName(0).toString().equals(".git")) {
            throw RookException.invalid("the path is inside the git directory");
        }
        return absolute;
    }

    @Override
    public void close() {
        git.close();
    }

    public static void deleteTree(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.delete(path);
            }
        } catch (IOException e) {
            throw RookException.invalid("the checkout could not be removed", e);
        }
    }
}
