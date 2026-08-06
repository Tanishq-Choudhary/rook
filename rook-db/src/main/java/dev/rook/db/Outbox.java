package dev.rook.db;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

public final class Outbox {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Outbox() {}

    public static long append(
            JdbcClient jdbc,
            String topic,
            String key,
            String payloadJson,
            Map<String, String> headers) {
        return jdbc.sql(
                        """
                        insert into outbox (topic, message_key, payload, headers)
                        values (:topic, :key, cast(:payload as jsonb), cast(:headers as jsonb))
                        returning id
                        """)
                .param("topic", topic)
                .param("key", key)
                .param("payload", payloadJson)
                .param("headers", MAPPER.writeValueAsString(headers))
                .query(Long.class)
                .single();
    }
}
