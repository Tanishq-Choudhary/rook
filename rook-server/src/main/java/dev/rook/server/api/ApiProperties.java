package dev.rook.server.api;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rook.api")
public record ApiProperties(Duration streamPoll, Duration streamHeartbeat, Integer maxStreams) {

    public ApiProperties {
        streamPoll = streamPoll == null ? Duration.ofMillis(500) : streamPoll;
        streamHeartbeat = streamHeartbeat == null ? Duration.ofSeconds(15) : streamHeartbeat;
        maxStreams = maxStreams == null ? 200 : maxStreams;
        if (streamPoll.isNegative()
                || streamPoll.isZero()
                || streamHeartbeat.isNegative()
                || streamHeartbeat.isZero()
                || maxStreams < 1) {
            throw new IllegalArgumentException("stream settings must be positive");
        }
    }
}
