package dev.rook.server.workflow;

import dev.rook.changes.store.ChangeIngestion;
import dev.rook.workflow.JobRepository;
import dev.rook.workflow.StepHandler;
import dev.rook.workflow.WorkflowMetrics;
import dev.rook.workflow.fanout.ChangeSetFanout;
import dev.rook.workflow.fanout.FanoutConfig;
import dev.rook.workflow.fanout.FanoutConsumer;
import dev.rook.workflow.fanout.FanoutMetrics;
import dev.rook.workflow.outbox.OutboxRelay;
import dev.rook.workflow.outbox.RelayConfig;
import dev.rook.workflow.outbox.RelayMetrics;
import dev.rook.workflow.repo.RepositoryStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "rook.workflow",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WorkflowConfig {

    @Bean
    RepositoryStore repositoryStore(JdbcClient jdbc, TransactionTemplate tx) {
        return new RepositoryStore(jdbc, tx);
    }

    @Bean
    JobRepository jobRepository(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        return new JobRepository(jdbc, tx, clock);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression(
            "${rook.workflow.enabled:true} and !'${rook.role:all}'.equalsIgnoreCase('api')")
    static class Background {

        @Bean
        WorkflowMetrics workflowMetrics(MeterRegistry registry) {
            return new MicrometerWorkflowMetrics(registry);
        }

        @Bean
        RelayMetrics relayMetrics(MeterRegistry registry) {
            return new MicrometerRelayMetrics(registry);
        }

        @Bean
        FanoutMetrics fanoutMetrics(MeterRegistry registry) {
            return new MicrometerFanoutMetrics(registry);
        }

        @Bean(destroyMethod = "close")
        KafkaProducer<String, String> outboxProducer(WorkflowProperties properties) {
            return new KafkaProducer<>(
                    Map.of(
                            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            properties.bootstrapServers(),
                            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                            StringSerializer.class.getName(),
                            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                            StringSerializer.class.getName(),
                            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                            true,
                            ProducerConfig.ACKS_CONFIG,
                            "all"));
        }

        @Bean(destroyMethod = "close")
        OutboxRelay outboxRelay(
                DataSource dataSource,
                JdbcClient jdbc,
                TransactionTemplate tx,
                KafkaProducer<String, String> outboxProducer,
                Clock clock,
                RelayMetrics metrics,
                WorkflowProperties properties) {
            return new OutboxRelay(
                    dataSource,
                    jdbc,
                    tx,
                    outboxProducer,
                    clock,
                    RelayConfig.standard().withPollInterval(properties.relayPollInterval()),
                    metrics);
        }

        @Bean
        FanoutConfig fanoutConfig(WorkflowProperties properties) {
            return FanoutConfig.standard()
                    .withTopic(ChangeIngestion.TOPIC)
                    .withGroupId(properties.fanoutGroup());
        }

        @Bean
        ChangeSetFanout changeSetFanout(
                JdbcClient jdbc,
                TransactionTemplate tx,
                RepositoryStore repositories,
                JobRepository jobs,
                FanoutConfig config) {
            return new ChangeSetFanout(jdbc, tx, repositories, jobs, config);
        }

        @Bean(destroyMethod = "close")
        FanoutConsumer fanoutConsumer(
                WorkflowProperties properties,
                ChangeSetFanout fanout,
                FanoutConfig config,
                FanoutMetrics metrics) {
            return new FanoutConsumer(properties.bootstrapServers(), fanout, config, metrics);
        }

        @Bean(destroyMethod = "close")
        WorkerRuntime workerRuntime(
                JobRepository jobs,
                ObjectProvider<StepHandler> handlers,
                WorkflowProperties properties,
                WorkflowMetrics metrics) {
            return new WorkerRuntime(jobs, handlers.orderedStream().toList(), properties, metrics);
        }

        @Bean
        WorkflowLifecycle workflowLifecycle(
                WorkflowProperties properties,
                OutboxRelay relay,
                FanoutConsumer fanout,
                WorkerRuntime workers) {
            return new WorkflowLifecycle(
                    properties.bootstrapServers(),
                    Map.of(ChangeIngestion.TOPIC, properties.topicPartitions()),
                    relay,
                    fanout,
                    workers,
                    properties.shutdownGrace());
        }

        @Bean
        WorkflowGauges workflowGauges(JobRepository jobs, JdbcClient jdbc, WorkerRuntime workers) {
            return new WorkflowGauges(jobs, jdbc, workers);
        }

        @Bean
        WorkflowHealthIndicator workflowHealthIndicator(WorkerRuntime workers, JdbcClient jdbc) {
            return new WorkflowHealthIndicator(workers, jdbc);
        }
    }
}
