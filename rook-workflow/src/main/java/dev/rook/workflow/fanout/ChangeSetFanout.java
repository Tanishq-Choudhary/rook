package dev.rook.workflow.fanout;

import dev.rook.core.id.Ids;
import dev.rook.workflow.JobRepository;
import dev.rook.workflow.repo.RepositoryStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public class ChangeSetFanout {

    public static final String CONSUMER = "fanout";

    private static final Logger log = LoggerFactory.getLogger(ChangeSetFanout.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RepositoryStore repositories;
    private final JobRepository jobs;
    private final FanoutConfig config;

    public ChangeSetFanout(
            JdbcClient jdbc,
            TransactionTemplate tx,
            RepositoryStore repositories,
            JobRepository jobs,
            FanoutConfig config) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.repositories = repositories;
        this.jobs = jobs;
        this.config = config;
    }

    public FanoutResult process(String messageId, String payload) {
        if (messageId == null || messageId.isBlank()) {
            return reject(null, payload, "message has no messageId header");
        }
        ChangeSetMessage message;
        try {
            message = ChangeSetMessage.parse(payload);
        } catch (RuntimeException e) {
            return reject(messageId, payload, "unreadable payload: " + e.getMessage());
        }
        return tx.execute(status -> apply(messageId, payload, message));
    }

    private FanoutResult apply(String messageId, String payload, ChangeSetMessage message) {
        int claimed =
                jdbc.sql(
                                """
                                insert into processed_message (consumer, message_id)
                                values (:consumer, :id) on conflict do nothing
                                """)
                        .param("consumer", CONSUMER)
                        .param("id", messageId)
                        .update();
        if (claimed == 0) {
            return FanoutResult.of(FanoutResult.Outcome.DUPLICATE);
        }
        if (!changeSetExists(message.changeSetId())) {
            recordDeadLetter(messageId, payload, "unknown change set " + message.changeSetId());
            return FanoutResult.of(FanoutResult.Outcome.REJECTED);
        }
        if (!message.requiresMigration()) {
            return FanoutResult.of(FanoutResult.Outcome.NOT_ACTIONABLE);
        }
        int created = 0;
        int existing = 0;
        for (RepositoryStore.Repository repository : repositories.watching(message.sourceId())) {
            JobRepository.CreateResult result =
                    jobs.create(
                            message.changeSetId(),
                            repository.id(),
                            message.priority(),
                            config.maxAttempts());
            if (result.created()) {
                created++;
            } else {
                existing++;
            }
        }
        FanoutResult.Outcome outcome =
                created > 0 ? FanoutResult.Outcome.JOBS_CREATED : FanoutResult.Outcome.NO_NEW_JOBS;
        return new FanoutResult(outcome, created, existing);
    }

    private boolean changeSetExists(UUID id) {
        return jdbc.sql("select count(*) from change_set where id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private FanoutResult reject(String messageId, String payload, String reason) {
        tx.executeWithoutResult(status -> recordDeadLetter(messageId, payload, reason));
        return FanoutResult.of(FanoutResult.Outcome.REJECTED);
    }

    private void recordDeadLetter(String messageId, String payload, String reason) {
        jdbc.sql(
                        """
                        insert into dead_letter (id, job_id, source, reason, payload)
                        values (:id, null, 'fanout', :reason,
                                jsonb_build_object('messageId', cast(:messageId as text),
                                                   'payload', cast(:payload as text)))
                        """)
                .param("id", Ids.newId())
                .param("reason", reason)
                .param("messageId", messageId)
                .param("payload", payload)
                .update();
        log.error("fanout rejected message {}: {}", messageId, reason);
    }
}
