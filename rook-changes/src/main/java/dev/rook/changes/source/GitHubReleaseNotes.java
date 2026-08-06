package dev.rook.changes.source;

import dev.rook.changes.feed.ReleaseEntry;
import dev.rook.changes.feed.ReleaseFeedParser;
import dev.rook.changes.http.FetchResult;
import dev.rook.changes.http.SafeHttpFetcher;
import dev.rook.core.error.RookException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class GitHubReleaseNotes implements ReleaseNotesProvider {

    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_.{}+-]+");

    private final SafeHttpFetcher fetcher;
    private final String apiBase;

    public GitHubReleaseNotes(SafeHttpFetcher fetcher, String apiBase) {
        this.fetcher = fetcher;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
    }

    @Override
    public Optional<ReleaseEntry> forVersion(WatchedSource source, String version) {
        String repo = source.configText("releaseNotesRepo");
        if (repo == null) {
            return Optional.empty();
        }
        if (!REPO.matcher(repo).matches()) {
            throw RookException.invalid("releaseNotesRepo must look like owner/name");
        }
        String pattern = source.configText("releaseNotesTag");
        String tag = (pattern == null ? "v{version}" : pattern).replace("{version}", version);
        if (!TAG.matcher(tag).matches()) {
            throw RookException.invalid("release tag contains unexpected characters");
        }
        FetchResult result =
                fetcher.get(
                        URI.create(apiBase + "/repos/" + repo + "/releases/tags/" + tag),
                        Map.of("Accept", "application/vnd.github+json"));
        if (result.status() == 404) {
            return Optional.empty();
        }
        if (!result.ok()) {
            throw RookException.unavailable(
                    "release notes lookup answered " + result.status(), null);
        }
        return Optional.of(ReleaseFeedParser.parseGitHubRelease(result.text()));
    }
}
