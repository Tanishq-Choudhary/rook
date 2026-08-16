package dev.rook.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class TestClock extends Clock {

    private final AtomicReference<Instant> now;

    public TestClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public static TestClock at(String instant) {
        return new TestClock(Instant.parse(instant));
    }

    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
