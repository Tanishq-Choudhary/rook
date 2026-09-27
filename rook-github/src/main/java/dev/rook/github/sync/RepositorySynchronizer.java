package dev.rook.github.sync;

import dev.rook.changes.source.WatchedSource;
import dev.rook.changes.store.SourceRepository;
import dev.rook.codeintel.project.Dependency;
import dev.rook.codeintel.project.RepoProfile;
import dev.rook.codeintel.project.RookConfig;
import dev.rook.core.change.SourceKind;
import dev.rook.core.error.RookException;
import dev.rook.github.api.GitHubClient;
import dev.rook.github.auth.TokenSource;
import dev.rook.github.git.GitCheckout;
import dev.rook.github.webhook.WebhookProcessor;
import dev.rook.workflow.repo.RepositoryStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepositorySynchronizer implements WebhookProcessor.PushListener {

    public record SyncResult(int added, int disabled) {}

    public record WatchResult(
            Set<String> watching, List<String> unknownSources, boolean configValid) {}

    private static final Logger log = LoggerFactory.getLogger(RepositorySynchronizer.class);

    private final GitHubClient client;
    private final TokenSource tokens;
    private final RepositoryStore repositories;
    private final SourceRepository sources;
    private final Path workRoot;

    public RepositorySynchronizer(
            GitHubClient client,
            TokenSource tokens,
            RepositoryStore repositories,
            SourceRepository sources,
            Path workRoot) {
        this.client = client;
        this.tokens = tokens;
        this.repositories = repositories;
        this.sources = sources;
        this.workRoot = workRoot;
    }

    public SyncResult syncInstallation(long githubInstallationId) {
        RepositoryStore.Installation installation =
                repositories
                        .findInstallation(githubInstallationId)
                        .orElseThrow(
                                () -> RookException.notFound("installation", githubInstallationId));
        List<GitHubClient.Repo> remote = client.installationRepositories(githubInstallationId);
        Set<Long> seen = new HashSet<>();
        int added = 0;
        Set<Long> known = new HashSet<>();
        repositories.list(installation.id()).forEach(r -> known.add(r.githubRepoId()));
        for (GitHubClient.Repo repo : remote) {
            seen.add(repo.id());
            if (!known.contains(repo.id())) {
                added++;
            }
            repositories.upsertRepository(
                    installation.id(), repo.id(), repo.fullName(), repo.defaultBranch());
        }
        int disabled = 0;
        for (RepositoryStore.Repository stored : repositories.list(installation.id())) {
            if (stored.enabled()
                    && stored.githubRepoId() != null
                    && !seen.contains(stored.githubRepoId())) {
                repositories.disableRepository(stored.githubRepoId());
                disabled++;
            }
        }
        return new SyncResult(added, disabled);
    }

    @Override
    public void defaultBranchPushed(UUID repositoryId, String headSha) {
        RepositoryStore.Repository repository =
                repositories
                        .find(repositoryId)
                        .orElseThrow(() -> RookException.notFound("repository", repositoryId));
        RepositoryStore.Installation installation = installationOf(repository);
        GitHubClient.Repo remote =
                client.repository(installation.githubInstallationId(), repository.fullName());
        Path directory = workRoot.resolve("sync-" + repositoryId);
        GitCheckout.deleteTree(directory);
        try {
            Files.createDirectories(workRoot);
        } catch (IOException e) {
            throw RookException.invalid("the work directory could not be created", e);
        }
        try (GitCheckout checkout =
                GitCheckout.clone(
                        remote.cloneUrl(),
                        directory,
                        repository.defaultBranch(),
                        tokens.tokenFor(installation.githubInstallationId()))) {
            refreshWatches(repositoryId, checkout.directory());
            repositories.markIndexed(repositoryId, checkout.headSha());
        } finally {
            GitCheckout.deleteTree(directory);
        }
    }

    public WatchResult refreshWatches(UUID repositoryId, Path checkout) {
        RepoProfile profile;
        try {
            profile = RepoProfile.detect(checkout);
        } catch (RookException e) {
            log.warn(
                    "repository {} has an unreadable configuration, keeping its watches",
                    repositoryId);
            return new WatchResult(
                    new TreeSet<>(repositories.watches(repositoryId)), List.of(), false);
        }
        List<WatchedSource> all = sources.list();
        Set<String> known = new HashSet<>();
        all.forEach(source -> known.add(source.id()));
        Set<String> watching = new TreeSet<>();
        List<String> unknown = new ArrayList<>();
        for (RookConfig.Watch watch : profile.config().watch()) {
            if (known.contains(watch.source())) {
                watching.add(watch.source());
            } else {
                unknown.add(watch.source());
            }
        }
        Set<String> coordinates = new HashSet<>();
        for (Dependency dependency : profile.scan().dependencies()) {
            coordinates.add(dependency.coordinates());
        }
        for (WatchedSource source : all) {
            if (source.kind() == SourceKind.MAVEN && coordinates.contains(coordinatesOf(source))) {
                watching.add(source.id());
            }
        }
        repositories.replaceWatches(repositoryId, watching);
        return new WatchResult(watching, unknown, true);
    }

    private RepositoryStore.Installation installationOf(RepositoryStore.Repository repository) {
        return repositories
                .installation(repository.installationId())
                .orElseThrow(
                        () -> RookException.notFound("installation", repository.installationId()));
    }

    private static String coordinatesOf(WatchedSource source) {
        return source.configText("groupId") + ":" + source.configText("artifactId");
    }
}
