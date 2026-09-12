package dev.rook.verify.run;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

final class TarExtractor {

    private static final int BLOCK = 512;

    private final int maxFiles;
    private final long maxBytes;

    TarExtractor(int maxFiles, long maxBytes) {
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
    }

    int extract(byte[] gzippedTar, Path target) throws IOException {
        Path base = target.toAbsolutePath().normalize();
        int files = 0;
        long total = 0;
        String longName = null;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzippedTar))) {
            byte[] header = new byte[BLOCK];
            while (readFully(in, header)) {
                if (isZero(header)) {
                    break;
                }
                String name = text(header, 0, 100);
                long size = octal(header, 124, 12);
                char type = (char) (header[156] == 0 ? '0' : header[156]);
                byte[] data = readData(in, size);
                if (type == 'L') {
                    longName = text(data, 0, data.length);
                    continue;
                }
                String entry = longName != null ? longName : name;
                longName = null;
                if (type != '0' && type != '\0') {
                    continue;
                }
                total += size;
                if (++files > maxFiles || total > maxBytes) {
                    throw new IOException("the exported reports are too large");
                }
                Path destination = base.resolve(entry).normalize();
                if (entry.startsWith("/") || !destination.startsWith(base)) {
                    throw new IOException("unsafe path in the exported reports: " + entry);
                }
                Files.createDirectories(destination.getParent());
                Files.write(destination, data);
            }
        }
        return files;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > Integer.MAX_VALUE - BLOCK) {
            throw new IOException("entry is too large");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(size, 1 << 20));
        byte[] block = new byte[BLOCK];
        long remaining = size;
        while (remaining > 0) {
            if (!readFully(in, block)) {
                throw new IOException("truncated archive");
            }
            int used = (int) Math.min(BLOCK, remaining);
            out.write(block, 0, used);
            remaining -= used;
        }
        return out.toByteArray();
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) {
                return read > 0 && read == buffer.length;
            }
            read += n;
        }
        return true;
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String text(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] bytes, int offset, int length) throws IOException {
        String value = text(bytes, offset, length).strip();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value, 8);
        } catch (NumberFormatException e) {
            throw new IOException("bad size field in the archive", e);
        }
    }
}
