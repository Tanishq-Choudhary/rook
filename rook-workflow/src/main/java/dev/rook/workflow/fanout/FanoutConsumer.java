package dev.rook.workflow.fanout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FanoutConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FanoutConsumer.class);

    private final Map<String, Object> properties;
    private final ChangeSetFanout fanout;
    private final FanoutConfig config;
    private final FanoutMetrics metrics;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile KafkaConsumer<String, String> active;
    private Thread loop;

    public FanoutConsumer(
            String bootstrapServers,
            ChangeSetFanout fanout,
            FanoutConfig config,
            FanoutMetrics metrics) {
        this(properties(bootstrapServers, config), fanout, config, metrics);
    }

    FanoutConsumer(
            Map<String, Object> properties,
            ChangeSetFanout fanout,
            FanoutConfig config,
            FanoutMetrics metrics) {
        this.properties = properties;
        this.fanout = fanout;
        this.config = config;
        this.metrics = metrics;
    }

    public static Map<String, Object> properties(String bootstrapServers, FanoutConfig config) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.groupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }

    public synchronized void start() {
        if (loop != null) {
            return;
        }
        loop = Thread.ofPlatform().daemon().name("fanout-consumer").start(this::run);
    }

    private void run() {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            active = consumer;
            consumer.subscribe(List.of(config.topic()));
            while (stopped.getCount() > 0) {
                ConsumerRecords<String, String> records = consumer.poll(config.pollTimeout());
                if (records.isEmpty()) {
                    continue;
                }
                Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
                boolean failed = false;
                for (TopicPartition partition : records.partitions()) {
                    for (ConsumerRecord<String, String> record : records.records(partition)) {
                        try {
                            handle(record);
                            commits.put(partition, new OffsetAndMetadata(record.offset() + 1));
                        } catch (RuntimeException e) {
                            log.warn(
                                    "fanout failed at {}-{}@{}, will retry: {}",
                                    record.topic(),
                                    record.partition(),
                                    record.offset(),
                                    e.getMessage());
                            metrics.failed();
                            consumer.seek(partition, record.offset());
                            failed = true;
                            break;
                        }
                    }
                }
                if (!commits.isEmpty()) {
                    consumer.commitSync(commits);
                }
                if (failed && waitForStop(config.retryPause())) {
                    break;
                }
            }
        } catch (WakeupException e) {
            log.debug("fanout consumer woken up for shutdown");
        } catch (RuntimeException e) {
            log.error("fanout consumer stopped on an unexpected error", e);
        } finally {
            active = null;
        }
    }

    private void handle(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("messageId");
        String messageId =
                header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        metrics.handled(fanout.process(messageId, record.value()));
    }

    private boolean waitForStop(Duration duration) {
        try {
            return stopped.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    public synchronized void stop() {
        stopped.countDown();
        KafkaConsumer<String, String> consumer = active;
        if (consumer != null) {
            consumer.wakeup();
        }
        if (loop != null) {
            try {
                loop.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            loop = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
