package dev.rook.core.random;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

public final class SharedRandom implements RandomGenerator {

    private static final SharedRandom INSTANCE = new SharedRandom();

    private SharedRandom() {}

    public static RandomGenerator get() {
        return INSTANCE;
    }

    @Override
    public long nextLong() {
        return ThreadLocalRandom.current().nextLong();
    }

    @Override
    public int nextInt() {
        return ThreadLocalRandom.current().nextInt();
    }

    @Override
    public double nextDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
