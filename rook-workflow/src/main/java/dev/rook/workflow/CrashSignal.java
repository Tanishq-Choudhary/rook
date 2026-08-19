package dev.rook.workflow;

import java.util.concurrent.atomic.AtomicBoolean;

public interface CrashSignal {

    CrashSignal NEVER = () -> false;

    boolean crashed();

    static Switch switchable() {
        return new Switch();
    }

    final class Switch implements CrashSignal {

        private final AtomicBoolean flag = new AtomicBoolean();

        public void crash() {
            flag.set(true);
        }

        @Override
        public boolean crashed() {
            return flag.get();
        }
    }
}
