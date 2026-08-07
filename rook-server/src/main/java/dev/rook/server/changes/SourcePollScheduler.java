package dev.rook.server.changes;

import dev.rook.changes.store.PollReport;
import dev.rook.changes.store.SourcePollService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(
        "!'${rook.role:all}'.equalsIgnoreCase('api') and ${rook.changes.scheduler-enabled:true}")
public class SourcePollScheduler {

    private static final Logger log = LoggerFactory.getLogger(SourcePollScheduler.class);

    private final SourcePollService service;
    private final ChangesProperties properties;

    SourcePollScheduler(SourcePollService service, ChangesProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${rook.changes.scheduler-delay:PT15S}")
    void tick() {
        try {
            List<PollReport> reports = service.pollDue(properties.batchSize());
            if (!reports.isEmpty()) {
                log.info(
                        "polled {} sources, {} failed",
                        reports.size(),
                        reports.stream().filter(PollReport::failed).count());
            }
        } catch (RuntimeException e) {
            log.error("source polling tick failed", e);
        }
    }
}
