package dev.rook.core.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

public final class Ids {

    private static final UuidV7Generator SHARED = new UuidV7Generator(Clock.systemUTC());

    private Ids() {}

    public static UUID newId() {
        return SHARED.next();
    }

    public static UuidV7Generator generator(Clock clock) {
        return new UuidV7Generator(clock);
    }

    public static long timestampMillis(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }

    public static final class UuidV7Generator {

        private static final int SEQUENCE_LIMIT = 1 << 12;

        private final Clock clock;
        private final SecureRandom random = new SecureRandom();
        private long lastMillis = -1;
        private int sequence;

        UuidV7Generator(Clock clock) {
            this.clock = clock;
        }

        public synchronized UUID next() {
            long now = clock.millis();
            if (now > lastMillis) {
                lastMillis = now;
                sequence = random.nextInt(SEQUENCE_LIMIT / 2);
            } else {
                sequence++;
                if (sequence >= SEQUENCE_LIMIT) {
                    lastMillis++;
                    sequence = random.nextInt(SEQUENCE_LIMIT / 2);
                }
            }
            long msb = (lastMillis << 16) | (0x7L << 12) | sequence;
            long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
            return new UUID(msb, lsb);
        }
    }
}
