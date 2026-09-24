package dev.rook.github.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.error.RookException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCheckoutTest {

    @TempDir Path temp;

    private Path remote;
    private String remoteUrl;

    @BeforeEach
    void seedRemote() throws Exception {
        remote = temp.resolve("remote.git");
        try (Git bare =
                Git.init()
                        .setBare(true)
                        .setInitialBranch("main")
                        .setDirectory(remote.toFile())
                        .call()) {
            remoteUrl = remote.toUri().toString();
        }
        Path seed = temp.resolve("seed");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(seed.toFile()).call()) {
            Files.createDirectories(seed.resolve("src"));
            Files.writeString(seed.resolve("src/A.java"), "class A {}\n");
            Files.writeString(seed.resolve("pom.xml"), "<project/>\n");
            git.add().addFilepattern(".").call();
            PersonIdent owner = new PersonIdent("Owner", "owner@example.test");
            git.commit()
                    .setMessage("initial")
                    .setAuthor(owner)
                    .setCommitter(owner)
                    .setSign(false)
                    .call();
            git.push().setRemote(remoteUrl).add("refs/heads/main:refs/heads/main").call();
        }
    }

    private static String text(Path file) throws IOException {
        return Files.readString(file).replace("\r\n", "\n");
    }

    private GitCheckout open(String name) {
        return GitCheckout.clone(remoteUrl, temp.resolve(name), "main", null);
    }

    private RevCommit remoteCommit(String branch) throws Exception {
        try (Git git = Git.open(remote.toFile())) {
            ObjectId id = git.getRepository().resolve("refs/heads/" + branch);
            try (var walk = new RevWalk(git.getRepository())) {
                return walk.parseCommit(id);
            }
        }
    }

    @Test
    void cloningChecksOutTheDefaultBranchWithItsFiles() throws IOException {
        try (GitCheckout checkout = open("work")) {
            assertThat(text(checkout.directory().resolve("src/A.java"))).isEqualTo("class A {}\n");
            assertThat(checkout.headSha()).hasSize(40);
            assertThat(checkout.remoteBranchSha("main")).contains(checkout.headSha());
            assertThat(checkout.remoteBranchSha("rook/none")).isEmpty();
        }
    }

    @Test
    void anUnreachableRemoteIsReportedAsAnError() {
        assertThatThrownBy(
                        () ->
                                GitCheckout.clone(
                                        temp.resolve("missing.git").toUri().toString(),
                                        temp.resolve("w"),
                                        "main",
                                        null))
                .isInstanceOf(RookException.class);
    }

    @Test
    void aBranchWithTheChangedFilesIsCommittedAsTheBotAndPushed() throws Exception {
        try (GitCheckout checkout = open("work")) {
            checkout.startBranch("rook/fix-1");
            checkout.write(
                    Map.of("src/A.java", "class A { int v; }\n", "src/B.java", "class B {}\n"),
                    List.of("pom.xml"));
            String sha =
                    checkout.commit(
                                    "migrate to the new api",
                                    List.of("src/A.java", "src/B.java", "pom.xml"))
                            .orElseThrow();
            assertThat(checkout.push("rook/fix-1")).isEqualTo(GitCheckout.PushStatus.PUSHED);
            RevCommit pushed = remoteCommit("rook/fix-1");
            assertThat(pushed.name()).isEqualTo(sha);
            assertThat(pushed.getAuthorIdent().getName()).isEqualTo("rook[bot]");
            assertThat(pushed.getCommitterIdent().getEmailAddress())
                    .isEqualTo("rook[bot]@users.noreply.github.com");
            assertThat(pushed.getFullMessage()).isEqualTo("migrate to the new api");
            assertThat(pushed.getParent(0).name()).isEqualTo(remoteCommit("main").name());
            assertThat(checkout.remoteBranchSha("rook/fix-1")).contains(sha);
        }
        try (Git verify =
                Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setBranch("rook/fix-1")
                        .setDirectory(temp.resolve("verify").toFile())
                        .call()) {
            assertThat(temp.resolve("verify/pom.xml")).doesNotExist();
            assertThat(text(temp.resolve("verify/src/B.java"))).isEqualTo("class B {}\n");
        }
    }

    @Test
    void pushingTheSameBranchAgainChangesNothing() {
        try (GitCheckout checkout = open("work")) {
            checkout.startBranch("rook/fix-1");
            checkout.write(Map.of("src/A.java", "class A { int v; }\n"), List.of());
            checkout.commit("change", List.of("src/A.java"));
            assertThat(checkout.push("rook/fix-1")).isEqualTo(GitCheckout.PushStatus.PUSHED);
            assertThat(checkout.push("rook/fix-1")).isEqualTo(GitCheckout.PushStatus.UP_TO_DATE);
        }
    }

    @Test
    void aSecondCommitFastForwardsTheRemoteBranch() throws Exception {
        try (GitCheckout checkout = open("work")) {
            checkout.startBranch("rook/fix-1");
            checkout.write(Map.of("src/A.java", "class A { int v; }\n"), List.of());
            checkout.commit("first", List.of("src/A.java"));
            checkout.push("rook/fix-1");
            checkout.write(Map.of("src/A.java", "class A { int w; }\n"), List.of());
            String second = checkout.commit("second", List.of("src/A.java")).orElseThrow();
            assertThat(checkout.push("rook/fix-1")).isEqualTo(GitCheckout.PushStatus.PUSHED);
            assertThat(remoteCommit("rook/fix-1").name()).isEqualTo(second);
        }
    }

    @Test
    void aDivergedBranchIsRejectedRatherThanOverwritten() throws Exception {
        try (GitCheckout first = open("first")) {
            first.startBranch("rook/fix-1");
            first.write(Map.of("src/A.java", "class A { int v; }\n"), List.of());
            first.commit("from first", List.of("src/A.java"));
            first.push("rook/fix-1");
        }
        String before = remoteCommit("rook/fix-1").name();
        try (GitCheckout second = open("second")) {
            second.startBranch("rook/fix-1");
            second.write(Map.of("src/A.java", "class A { int other; }\n"), List.of());
            second.commit("from second", List.of("src/A.java"));
            assertThat(second.push("rook/fix-1")).isEqualTo(GitCheckout.PushStatus.REJECTED);
        }
        assertThat(remoteCommit("rook/fix-1").name()).isEqualTo(before);
    }

    @Test
    void committingWithoutARealChangeProducesNoCommit() {
        try (GitCheckout checkout = open("work")) {
            checkout.startBranch("rook/noop");
            checkout.write(Map.of("src/A.java", "class A {}\n"), List.of());
            assertThat(checkout.commit("nothing", List.of("src/A.java"))).isEmpty();
            assertThat(checkout.commit("nothing", List.of())).isEmpty();
        }
    }

    @Test
    void theDefaultBranchCanNeverBePushed() {
        try (GitCheckout checkout = open("work")) {
            assertThatThrownBy(() -> checkout.push("main"))
                    .isInstanceOf(RookException.class)
                    .hasMessageContaining("default branch");
        }
    }

    @Test
    void unsafeBranchNamesAreRefused() {
        for (String name :
                List.of(
                        "",
                        "a..b",
                        "-rf",
                        "bad name",
                        "ends/",
                        "x.lock",
                        "a//b",
                        "../escape",
                        "a;b")) {
            assertThatThrownBy(() -> GitCheckout.checkBranch(name))
                    .as(name)
                    .isInstanceOf(RookException.class);
        }
        GitCheckout.checkBranch("rook/acme-sdk-2.0.0");
    }

    @Test
    void pathsCannotLeaveTheRepositoryOrReachTheGitDirectory() {
        try (GitCheckout checkout = open("work")) {
            for (String path :
                    List.of(
                            "../outside.txt",
                            "src/../../outside.txt",
                            ".git/config",
                            ".git/hooks/pre-commit",
                            "")) {
                assertThatThrownBy(() -> checkout.write(Map.of(path, "x"), List.of()))
                        .as(path)
                        .isInstanceOf(RookException.class);
            }
            assertThatThrownBy(() -> checkout.write(Map.of(), List.of("../outside.txt")))
                    .isInstanceOf(RookException.class);
            assertThat(temp.resolve("outside.txt")).doesNotExist();
        }
    }

    @Test
    void credentialsAreNotWrittenIntoTheCheckoutConfiguration() throws IOException {
        try (GitCheckout checkout =
                GitCheckout.clone(
                        remoteUrl, temp.resolve("work"), "main", "ghs_secret_token_value")) {
            String config = Files.readString(checkout.directory().resolve(".git/config"));
            assertThat(config)
                    .doesNotContain("ghs_secret_token_value")
                    .doesNotContain("x-access-token");
        }
    }

    @Test
    void checkoutsCanBeRemovedIncludingReadOnlyGitObjects() {
        try (GitCheckout checkout = open("work")) {
            assertThat(checkout.directory()).exists();
        }
        GitCheckout.deleteTree(temp.resolve("work"));
        assertThat(temp.resolve("work")).doesNotExist();
        GitCheckout.deleteTree(temp.resolve("work"));
    }
}
