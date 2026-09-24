package dev.rook.github.webhook;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;

public class DeliveryLog {

    private final JdbcClient jdbc;
    private final Clock clock;

    public DeliveryLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean begin(String deliveryId, String event) {
        return jdbc.sql(
                                """
                                insert into webhook_delivery (id, event, received_at)
                                values (:id, :event, :now)
                                on conflict (id) do nothing
                                """)
                        .param("id", deliveryId)
                        .param("event", event)
                        .param("now", Timestamp.from(clock.instant()))
                        .update()
                > 0;
    }

    public void complete(String deliveryId, String outcome) {
        jdbc.sql("update webhook_delivery set outcome = :outcome where id = :id")
                .param("outcome", outcome)
                .param("id", deliveryId)
                .update();
    }

    public void abandon(String deliveryId) {
        jdbc.sql("delete from webhook_delivery where id = :id").param("id", deliveryId).update();
    }

    public int purgeOlderThan(Duration age) {
        return jdbc.sql("delete from webhook_delivery where received_at < :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(age)))
                .update();
    }
}
