package dev.rook.changes.store;

import dev.rook.changes.source.PollOutcome;
import dev.rook.changes.source.SnapshotStore;
import dev.rook.changes.source.SourcePoller;
import dev.rook.changes.source.WatchedSource;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.error.RookException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SourcePollService {

    private static final Logger log = LoggerFactory.getLogger(SourcePollService.class);

    private final SourceRepository sources;
    private final ChangeIngestion ingestion;
    private final SnapshotStore snapshots;
    private final Map<SourceKind, SourcePoller> pollers;
    private final PollMetrics metrics;
    private final Clock clock;

    public SourcePollService(
            SourceRepository sources,
            ChangeIngestion ingestion,
            SnapshotStore snapshots,
            Map<SourceKind, SourcePoller> pollers,
            PollMetrics metrics,
            Clock clock) {
        this.sources = sources;
        this.ingestion = ingestion;
        this.snapshots = snapshots;
        this.pollers = Map.copyOf(pollers);
        this.metrics = metrics;
        this.clock = clock;
    }

    public List<PollReport> pollDue(int batchSize) {
        List<PollReport> reports = new ArrayList<>();
        for (WatchedSource source : sources.claimDue(clock.instant(), batchSize)) {
            reports.add(poll(source));
        }
        return reports;
    }

    public PollReport pollNow(String sourceId) {
        WatchedSource source =
                sources.find(sourceId)
                        .orElseThrow(() -> RookException.notFound("source", sourceId));
        return poll(source);
    }

    private PollReport poll(WatchedSource source) {
        Instant started = clock.instant();
        SourcePoller poller = pollers.get(source.kind());
        if (poller == null) {
            return fail(source, started, "no poller for " + source.kind());
        }
        try {
            PollOutcome outcome = poller.poll(source);
            ingestion.apply(source, outcome, snapshots);
            for (ChangeSet set : outcome.changeSets()) {
                metrics.ingested(set, true);
            }
            metrics.polled(
                    source.kind(),
                    outcome.status().name().toLowerCase(),
                    Duration.between(started, clock.instant()));
            return new PollReport(source.id(), outcome.status(), outcome.changeSets().size(), null);
        } catch (RuntimeException e) {
            return fail(
                    source,
                    started,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private PollReport fail(WatchedSource source, Instant started, String message) {
        log.warn("poll of {} failed: {}", source.id(), message);
        metrics.polled(source.kind(), "failed", Duration.between(started, clock.instant()));
        ingestion.recordFailure(source.id(), message);
        return PollReport.failure(source.id(), message);
    }
}
