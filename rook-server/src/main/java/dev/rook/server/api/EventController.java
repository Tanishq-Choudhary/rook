package dev.rook.server.api;

import dev.rook.control.auth.Principal;
import dev.rook.control.auth.Scope;
import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import dev.rook.server.api.Dtos.StreamEvent;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
@EnableConfigurationProperties(ApiProperties.class)
@ConditionalOnProperty(
        prefix = "rook.workflow",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class EventController {

    private static final int BATCH = 100;

    private final ApiAccess access;
    private final ApiQueries queries;
    private final ApiProperties properties;
    private final AtomicInteger open = new AtomicInteger();

    EventController(
            ApiAccess access, ApiQueries queries, ApiProperties properties, MeterRegistry meters) {
        meters.gauge("rook.api.streams.open", open);
        this.access = access;
        this.queries = queries;
        this.properties = properties;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter all(
            HttpServletRequest request,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) UUID installation) {
        Principal principal = access.principal(request, Scope.READ);
        UUID filter = access.installationFilter(principal, installation);
        return stream(
                resumeFrom(lastEventId, queries::latestEventId),
                after -> queries.eventsAfter(filter, after, BATCH));
    }

    @GetMapping(path = "/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter job(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        Principal principal = access.principal(request, Scope.READ);
        UUID owner =
                queries.jobInstallation(id).orElseThrow(() -> RookException.notFound("job", id));
        access.ensureVisible(principal, owner);
        return stream(
                resumeFrom(lastEventId, () -> 0),
                after -> queries.jobEventsAfter(id, after, BATCH));
    }

    static long resumeFrom(String lastEventId, LongSupplier fallback) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                return Math.max(0, Long.parseLong(lastEventId.strip()));
            } catch (NumberFormatException e) {
                throw RookException.invalid("Last-Event-ID is not a number");
            }
        }
        return fallback.getAsLong();
    }

    private SseEmitter stream(long start, LongFunction<List<StreamEvent>> fetch) {
        if (open.incrementAndGet() > properties.maxStreams()) {
            open.decrementAndGet();
            throw new RookException(ErrorCode.RATE_LIMITED, "too many open event streams");
        }
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean running = new AtomicBoolean(true);
        Runnable stop =
                () -> {
                    if (running.getAndSet(false)) {
                        open.decrementAndGet();
                    }
                };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(error -> stop.run());
        Thread.ofVirtual()
                .name("sse-stream")
                .start(() -> pump(emitter, running, start, fetch, stop));
        return emitter;
    }

    private void pump(
            SseEmitter emitter,
            AtomicBoolean running,
            long start,
            LongFunction<List<StreamEvent>> fetch,
            Runnable stop) {
        long last = start;
        long lastSignal = System.nanoTime();
        try {
            while (running.get()) {
                List<StreamEvent> events = fetch.apply(last);
                for (StreamEvent event : events) {
                    emitter.send(
                            SseEmitter.event()
                                    .id(Long.toString(event.id()))
                                    .name("job")
                                    .data(Json.write(event), MediaType.APPLICATION_JSON));
                    last = event.id();
                    lastSignal = System.nanoTime();
                }
                if (events.size() < BATCH) {
                    Thread.sleep(properties.streamPoll());
                    if (System.nanoTime() - lastSignal >= properties.streamHeartbeat().toNanos()) {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                        lastSignal = System.nanoTime();
                    }
                }
            }
        } catch (IOException | IllegalStateException e) {
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stop.run();
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                running.set(false);
            }
        }
    }
}
