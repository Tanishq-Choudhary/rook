package dev.rook.server.workflow;

import dev.rook.changes.source.WatchedSource;
import dev.rook.changes.store.ChangeIngestion;
import dev.rook.changes.store.SourceRepository;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.id.Ids;
import dev.rook.workflow.repo.RepositoryStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WorkflowScenario {

    private WorkflowScenario() {}

    static UUID watchedRepository(
            SourceRepository sources, RepositoryStore store, String sourceId, long githubRepoId) {
        sources.register(
                new WatchedSource(
                        sourceId,
                        SourceKind.OPENAPI,
                        sourceId,
                        "http://example.test/" + sourceId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        900,
                        true));
        RepositoryStore.Installation installation =
                store.upsertInstallation(githubRepoId, "acme-" + githubRepoId);
        RepositoryStore.Repository repository =
                store.upsertRepository(
                        installation.id(), githubRepoId, "acme/shop-" + githubRepoId, "main");
        store.watch(repository.id(), sourceId);
        return repository.id();
    }

    static UUID breakingChange(ChangeIngestion ingestion, String sourceId) {
        ChangeSet set =
                new ChangeSet(
                        Ids.newId(),
                        sourceId,
                        SourceKind.OPENAPI,
                        "1.0.0",
                        "2.0.0",
                        List.of(
                                ChangeEvent.of(
                                        sourceId,
                                        "1.0.0",
                                        "2.0.0",
                                        ChangeKind.OPERATION_REMOVED,
                                        SymbolRef.operation("GET", "/v1/charges"),
                                        Map.of("operationId", "listCharges"),
                                        "removed charges listing")),
                        List.of(),
                        Instant.now());
        return ingestion.ingest(set).changeSetId();
    }
}
