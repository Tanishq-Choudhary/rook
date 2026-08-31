package dev.rook.verify.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class OutputCapture implements Runnable {

    private final InputStream source;
    private final byte[] ring;
    private long total;

    OutputCapture(InputStream source, int capacity) {
        this.source = source;
        this.ring = new byte[capacity];
    }

    @Override
    public void run() {
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = source.read(chunk)) >= 0) {
                synchronized (this) {
                    for (int i = 0; i < read; i++) {
                        ring[(int) (total % ring.length)] = chunk[i];
                        total++;
                    }
                }
            }
        } catch (IOException e) {
            return;
        }
    }

    synchronized boolean truncated() {
        return total > ring.length;
    }

    synchronized String text() {
        if (total <= ring.length) {
            return new String(ring, 0, (int) total, StandardCharsets.UTF_8);
        }
        int start = (int) (total % ring.length);
        byte[] ordered = new byte[ring.length];
        System.arraycopy(ring, start, ordered, 0, ring.length - start);
        System.arraycopy(ring, 0, ordered, ring.length - start, start);
        return new String(ordered, StandardCharsets.UTF_8);
    }
}
