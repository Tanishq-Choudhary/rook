package dev.rook.server.api;

import dev.rook.control.policy.Decision;
import dev.rook.control.store.ApprovalStore;
import dev.rook.github.webhook.PullRequests;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class Dtos {

    private Dtos() {}

    public record Page<T>(List<T> items, int page, int size, long total) {

        public Page {
            items = List.copyOf(items);
        }
    }

    public record InstallationDto(
            UUID id,
            Long githubInstallationId,
            String accountLogin,
            String status,
            Instant createdAt) {}

    public record RepositoryDto(
            UUID id,
            UUID installationId,
            Long githubRepoId,
            String fullName,
            String defaultBranch,
            boolean enabled,
            String lastIndexedSha,
            List<String> watches) {

        public RepositoryDto {
            watches = List.copyOf(watches);
        }
    }

    public record SourceDto(
            String id,
            String kind,
            String displayName,
            String location,
            String lastVersion,
            Instant lastCheckedAt,
            String lastError,
            boolean enabled) {}

    public record ChangeSetSummary(
            UUID id,
            String sourceId,
            String fromVersion,
            String toVersion,
            String highestSeverity,
            boolean requiresMigration,
            Instant detectedAt,
            int events) {}

    public record EventDto(
            String fingerprint,
            String kind,
            String severity,
            String subjectKind,
            String subjectName,
            JsonNode attributes,
            String summary) {}

    public record ChangeSetDetail(
            ChangeSetSummary changeSet, List<EventDto> events, JsonNode notes) {

        public ChangeSetDetail {
            events = List.copyOf(events);
        }
    }

    public record JobSummary(
            UUID id,
            UUID changeSetId,
            UUID repositoryId,
            String repository,
            String state,
            int attempt,
            int priority,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt) {}

    public record TimelineEntry(long id, Instant at, String kind, String state, JsonNode data) {}

    public record ImpactDto(String decision, String summary, String baseSha, JsonNode report) {}

    public record ProposalDto(
            String strategy,
            String status,
            String summary,
            Double confidence,
            String diff,
            List<String> changedFiles,
            List<String> unresolved,
            long costMicros,
            JsonNode trace) {

        public ProposalDto {
            changedFiles = List.copyOf(changedFiles);
            unresolved = List.copyOf(unresolved);
        }
    }

    public record VerificationDto(
            boolean passed,
            boolean supported,
            String summary,
            Integer baselineRun,
            Integer baselineFailed,
            Integer patchedRun,
            Integer patchedFailed,
            long elapsedMillis,
            JsonNode report) {}

    public record JobDetail(
            JobSummary job,
            List<TimelineEntry> timeline,
            ImpactDto impact,
            ProposalDto proposal,
            VerificationDto verification,
            Decision policy,
            ApprovalStore.Approval approval,
            PullRequests.Record pullRequest,
            long spendMicros) {

        public JobDetail {
            timeline = List.copyOf(timeline);
        }
    }

    public record UsageDto(
            Instant monthStart,
            long spentMicros,
            long reservedMicros,
            long monthlyCapMicros,
            long remainingMicros,
            long modelMicros,
            long sandboxMicros) {}

    public record AuditEntryDto(
            long seq,
            Instant at,
            String actor,
            String action,
            String subject,
            JsonNode data,
            String hash) {}

    public record AuditVerificationDto(
            boolean intact, long checked, Long firstBadSeq, String problem, String head) {}
}
