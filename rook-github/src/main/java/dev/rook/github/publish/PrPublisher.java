package dev.rook.github.publish;

import dev.rook.core.error.RookException;
import dev.rook.github.api.GitHubClient;
import dev.rook.github.api.GitHubException;
import dev.rook.github.auth.TokenSource;
import dev.rook.github.git.GitCheckout;
import dev.rook.github.webhook.PullRequests;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrPublisher {

    public record Request(
            long installationId,
            UUID repositoryId,
            GitHubClient.Repo repository,
            UUID jobId,
            String slug,
            Map<String, String> files,
            List<String> deletions,
            PrReport report) {

        public Request {
            files = Map.copyOf(files);
            deletions = List.copyOf(deletions);
        }
    }

    public record Published(
            int number, String url, String branch, String headSha, boolean created) {}

    private static final Logger log = LoggerFactory.getLogger(PrPublisher.class);

    private final GitHubClient client;
    private final TokenSource tokens;
    private final PullRequests pullRequests;
    private final Path workRoot;

    public PrPublisher(
            GitHubClient client, TokenSource tokens, PullRequests pullRequests, Path workRoot) {
        this.client = client;
        this.tokens = tokens;
        this.pullRequests = pullRequests;
        this.workRoot = workRoot;
    }

    public static String branchName(UUID jobId, String slug) {
        String clean =
                slug == null
                        ? ""
                        : slug.toLowerCase(Locale.ROOT)
                                .replaceAll("[^a-z0-9]+", "-")
                                .replaceAll("^-+|-+$", "");
        if (clean.length() > 40) {
            clean = clean.substring(0, 40).replaceAll("-+$", "");
        }
        String id = jobId.toString().substring(0, 8);
        return "rook/" + id + (clean.isEmpty() ? "" : "-" + clean);
    }

    public Published publish(Request request) {
        String branch = branchName(request.jobId(), request.slug());
        String fullName = request.repository().fullName();
        Optional<GitHubClient.PullRequest> existing =
                client.findOpenPullRequest(request.installationId(), fullName, branch);
        if (existing.isPresent()) {
            return remember(request, branch, existing.get(), false);
        }
        String headSha = ensureBranch(request, branch);
        GitHubClient.PullRequest pull;
        boolean created = true;
        try {
            pull =
                    client.createPullRequest(
                            request.installationId(),
                            fullName,
                            PrBody.title(request.report()),
                            PrBody.render(request.report()),
                            branch,
                            request.repository().defaultBranch());
        } catch (GitHubException e) {
            if (!e.alreadyExists()) {
                throw e;
            }
            pull =
                    client.findOpenPullRequest(request.installationId(), fullName, branch)
                            .orElseThrow(() -> e);
            created = false;
        }
        GitHubClient.PullRequest withSha =
                new GitHubClient.PullRequest(
                        pull.number(),
                        pull.url(),
                        pull.state(),
                        pull.headSha().isEmpty() ? headSha : pull.headSha());
        Published published = remember(request, branch, withSha, created);
        reportChecks(request, published);
        return published;
    }

    private String ensureBranch(Request request, String branch) {
        Path directory = workRoot.resolve("publish-" + request.jobId());
        GitCheckout.deleteTree(directory);
        try {
            Files.createDirectories(workRoot);
        } catch (IOException e) {
            throw RookException.invalid("the work directory could not be created", e);
        }
        String token = tokens.tokenFor(request.installationId());
        try (GitCheckout checkout =
                GitCheckout.clone(
                        request.repository().cloneUrl(),
                        directory,
                        request.repository().defaultBranch(),
                        token)) {
            Optional<String> remote = checkout.remoteBranchSha(branch);
            if (remote.isPresent()) {
                return remote.get();
            }
            checkout.startBranch(branch);
            checkout.write(request.files(), request.deletions());
            List<String> paths = new ArrayList<>(request.files().keySet());
            paths.addAll(request.deletions());
            String message = PrBody.title(request.report());
            Optional<String> commit = checkout.commit(message, paths);
            if (commit.isEmpty()) {
                throw RookException.invalid("the patch does not change the repository");
            }
            if (checkout.push(branch) == GitCheckout.PushStatus.REJECTED) {
                throw RookException.conflict(
                        "the branch " + branch + " already exists with other changes");
            }
            return commit.get();
        } finally {
            GitCheckout.deleteTree(directory);
        }
    }

    private Published remember(
            Request request, String branch, GitHubClient.PullRequest pull, boolean created) {
        pullRequests.record(
                request.jobId(),
                request.repositoryId(),
                pull.number(),
                pull.url(),
                branch,
                pull.headSha());
        return new Published(pull.number(), pull.url(), branch, pull.headSha(), created);
    }

    private void reportChecks(Request request, Published published) {
        PrReport.Verification v = request.report().verification();
        boolean passed = v.patchedFailed() == 0 && v.patchedRun() > 0;
        try {
            client.createCheckRun(
                    request.installationId(),
                    request.repository().fullName(),
                    published.headSha(),
                    "rook",
                    passed ? "success" : "neutral",
                    passed ? "Verified" : "Needs a look",
                    v.patchedRun()
                            + " tests ran after the change, "
                            + v.patchedFailed()
                            + " failed.");
        } catch (GitHubException e) {
            log.warn("check run was not recorded status={}", e.status());
        }
    }
}
