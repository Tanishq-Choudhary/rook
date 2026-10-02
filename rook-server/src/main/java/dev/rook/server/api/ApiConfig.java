package dev.rook.server.api;

import dev.rook.control.audit.AuditLog;
import dev.rook.control.store.ApprovalStore;
import dev.rook.github.webhook.PullRequests;
import dev.rook.pipeline.step.ApprovalService;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.workflow.JobRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class ApiConfig {

    @Bean
    ArtifactStore artifactStore(JdbcClient jdbc) {
        return new ArtifactStore(jdbc);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "rook.workflow",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    ApprovalService approvalService(ApprovalStore approvals, JobRepository jobs, AuditLog audit) {
        return new ApprovalService(approvals, jobs, audit);
    }

    @Bean
    PullRequests pullRequests(JdbcClient jdbc, Clock clock) {
        return new PullRequests(jdbc, clock);
    }
}
