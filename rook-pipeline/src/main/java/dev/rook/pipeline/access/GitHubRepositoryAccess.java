package dev.rook.pipeline.access;

import dev.rook.core.error.RookException;
import dev.rook.github.api.GitHubClient;
import dev.rook.github.auth.TokenSource;
import dev.rook.github.git.GitCheckout;
import dev.rook.workflow.repo.RepositoryStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class GitHubRepositoryAccess implements RepositoryAccess {

    private final RepositoryStore repositories;
    private final GitHubClient client;
    private final TokenSource tokens;

    public GitHubRepositoryAccess(
            RepositoryStore repositories, GitHubClient client, TokenSource tokens) {
        this.repositories = repositories;
        this.client = client;
        this.tokens = tokens;
    }

    @Override
    public Checkout checkout(UUID repositoryId, Path directory, String pinnedSha) {
        RepositoryStore.Repository repository =
                repositories
                        .find(repositoryId)
                        .orElseThrow(() -> RookException.notFound("repository", repositoryId));
        RepositoryStore.Installation installation =
                repositories
                        .installation(repository.installationId())
                        .orElseThrow(
                                () ->
                                        RookException.notFound(
                                                "installation", repository.installationId()));
        long githubInstallation = installation.githubInstallationId();
        GitHubClient.Repo remote = client.repository(githubInstallation, repository.fullName());
        GitCheckout.deleteTree(directory);
        try {
            Files.createDirectories(directory.getParent());
        } catch (IOException e) {
            throw RookException.invalid("the work directory could not be created", e);
        }
        try (GitCheckout checkout =
                GitCheckout.clone(
                        remote.cloneUrl(),
                        directory,
                        repository.defaultBranch(),
                        tokens.tokenFor(githubInstallation))) {
            if (pinnedSha != null) {
                checkout.detachAt(pinnedSha);
            }
            return new Checkout(
                    directory,
                    checkout.headSha(),
                    repository.fullName(),
                    repository.defaultBranch());
        }
    }
}
