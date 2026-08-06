package dev.rook.changes.source;

import dev.rook.changes.diff.DiffContext;
import dev.rook.changes.feed.ReleaseEntry;
import dev.rook.changes.feed.ReleaseNotes;
import dev.rook.changes.http.FetchResult;
import dev.rook.changes.http.SafeHttpFetcher;
import dev.rook.changes.jar.ApiDiffer;
import dev.rook.changes.jar.ApiSurface;
import dev.rook.changes.jar.ApiSurfaceReader;
import dev.rook.changes.version.Versions;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.MigrationNote;
import dev.rook.core.change.SourceKind;
import dev.rook.core.error.RookException;
import dev.rook.core.hash.Hashes;
import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MavenPoller implements SourcePoller {

    public static final int MAX_VERSIONS_PER_POLL = 5;

    private static final Logger log = LoggerFactory.getLogger(MavenPoller.class);

    private final SafeHttpFetcher metadataFetcher;
    private final SafeHttpFetcher artifactFetcher;
    private final SnapshotStore snapshots;
    private final ReleaseNotesProvider notes;
    private final Clock clock;

    public MavenPoller(
            SafeHttpFetcher metadataFetcher,
            SafeHttpFetcher artifactFetcher,
            SnapshotStore snapshots,
            ReleaseNotesProvider notes,
            Clock clock) {
        this.metadataFetcher = metadataFetcher;
        this.artifactFetcher = artifactFetcher;
        this.snapshots = snapshots;
        this.notes = notes;
        this.clock = clock;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.MAVEN;
    }

    @Override
    public PollOutcome poll(WatchedSource source) {
        String group = require(source, "groupId");
        String artifact = require(source, "artifactId");
        String base = trimTrailingSlash(source.location());
        URI metadataUri =
                URI.create(
                        base
                                + "/"
                                + group.replace('.', '/')
                                + "/"
                                + artifact
                                + "/maven-metadata.xml");
        Map<String, String> headers =
                source.etag() == null ? Map.of() : Map.of("If-None-Match", source.etag());
        FetchResult metadata = metadataFetcher.get(metadataUri, headers);
        if (metadata.notModified()) {
            return PollOutcome.unchanged(source.etag(), source.lastVersion());
        }
        if (!metadata.ok()) {
            throw RookException.unavailable(
                    "repository answered " + metadata.status() + " for " + metadataUri, null);
        }
        String etag = metadata.etag().orElse(null);
        List<String> versions = MavenMetadata.versions(metadata.text());
        boolean includePrerelease = source.configFlag("includePrerelease");

        if (source.lastVersion() == null) {
            Optional<String> latest = Versions.latest(versions, includePrerelease);
            if (latest.isEmpty()) {
                return PollOutcome.unchanged(etag, null);
            }
            Loaded loaded = load(source, base, group, artifact, latest.get());
            return new PollOutcome(
                    PollOutcome.Status.BASELINE,
                    List.of(),
                    loaded.toSave().stream().toList(),
                    etag,
                    latest.get());
        }

        List<String> news =
                Versions.newerThan(versions, source.lastVersion(), includePrerelease).stream()
                        .limit(MAX_VERSIONS_PER_POLL)
                        .toList();
        if (news.isEmpty()) {
            return PollOutcome.unchanged(etag, source.lastVersion());
        }

        Loaded previous = load(source, base, group, artifact, source.lastVersion());
        String from = source.lastVersion();
        List<ChangeSet> sets = new ArrayList<>();
        List<Snapshot> toSave = new ArrayList<>(previous.toSave().stream().toList());
        String last = from;
        for (String version : news) {
            Loaded next;
            try {
                next = load(source, base, group, artifact, version);
            } catch (RookException e) {
                if (last.equals(source.lastVersion())) {
                    throw e;
                }
                log.warn("stopping at {} for {}: {}", version, source.id(), e.getMessage());
                break;
            }
            sets.addAll(step(source, from, version, previous.surface(), next.surface()));
            next.toSave().ifPresent(toSave::add);
            previous = next;
            from = version;
            last = version;
        }
        return PollOutcome.changed(sets, toSave, etag, last);
    }

    private List<ChangeSet> step(
            WatchedSource source, String from, String to, ApiSurface before, ApiSurface after) {
        List<ChangeEvent> events =
                new ArrayList<>(ApiDiffer.diff(source.id(), from, to, before, after));
        List<MigrationNote> migrationNotes = new ArrayList<>();
        Optional<ReleaseEntry> entry = lookupNotes(source, to);
        if (entry.isPresent()) {
            ReleaseNotes.Result result = ReleaseNotes.fromEntry(source.id(), from, entry.get());
            DiffContext ctx = new DiffContext(source.id(), from, to);
            events = new ArrayList<>(ReleaseNotes.enrich(ctx, events, result.findings()));
            for (ChangeEvent advisory : result.events()) {
                events.add(
                        ChangeEvent.of(
                                source.id(),
                                from,
                                to,
                                advisory.kind(),
                                advisory.subject(),
                                advisory.attributes(),
                                advisory.summary()));
            }
            migrationNotes.addAll(result.notes());
        }
        if (events.isEmpty()) {
            return List.of();
        }
        return List.of(
                new ChangeSet(
                        Ids.newId(),
                        source.id(),
                        SourceKind.MAVEN,
                        from,
                        to,
                        events,
                        migrationNotes,
                        clock.instant()));
    }

    private Optional<ReleaseEntry> lookupNotes(WatchedSource source, String version) {
        try {
            return notes.forVersion(source, version);
        } catch (RookException e) {
            log.warn(
                    "release notes unavailable for {} {}: {}",
                    source.id(),
                    version,
                    e.getMessage());
            return Optional.empty();
        }
    }

    private Loaded load(
            WatchedSource source, String base, String group, String artifact, String version) {
        Optional<Snapshot> stored = snapshots.byVersion(source.id(), version);
        if (stored.isPresent()) {
            return new Loaded(
                    Json.read(
                            new String(stored.get().content(), StandardCharsets.UTF_8),
                            ApiSurface.class),
                    Optional.empty());
        }
        URI jar =
                URI.create(
                        base
                                + "/"
                                + group.replace('.', '/')
                                + "/"
                                + artifact
                                + "/"
                                + version
                                + "/"
                                + artifact
                                + "-"
                                + version
                                + ".jar");
        FetchResult result = artifactFetcher.get(jar);
        if (!result.ok()) {
            throw RookException.unavailable(
                    "repository answered " + result.status() + " for " + jar, null);
        }
        ApiSurface surface = ApiSurfaceReader.read(new ByteArrayInputStream(result.body()));
        byte[] content = Json.write(surface).getBytes(StandardCharsets.UTF_8);
        Snapshot snapshot =
                new Snapshot(
                        Ids.newId(),
                        source.id(),
                        version,
                        Hashes.sha256Hex(result.body()),
                        content,
                        clock.instant());
        Optional<Snapshot> sameJar = snapshots.byHash(source.id(), snapshot.contentHash());
        return new Loaded(surface, sameJar.isPresent() ? Optional.empty() : Optional.of(snapshot));
    }

    private static String require(WatchedSource source, String key) {
        String value = source.configText(key);
        if (value == null || value.isBlank()) {
            throw RookException.invalid("maven source " + source.id() + " needs config " + key);
        }
        return value;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record Loaded(ApiSurface surface, Optional<Snapshot> toSave) {}
}
