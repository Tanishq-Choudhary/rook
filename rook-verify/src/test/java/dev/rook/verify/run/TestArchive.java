package dev.rook.verify.run;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

final class TestArchive {

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    TestArchive add(String path, String content) {
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    byte[] tar() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        files.forEach(
                (path, data) -> {
                    if (path.length() > 100) {
                        byte[] name = (path + "\0").getBytes(StandardCharsets.UTF_8);
                        write(out, "././@LongLink", name, 'L');
                    }
                    write(out, path.length() > 100 ? path.substring(0, 100) : path, data, '0');
                });
        out.write(new byte[1024], 0, 1024);
        return out.toByteArray();
    }

    byte[] gzipped() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(tar());
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String base64() {
        return Base64.getEncoder().encodeToString(gzipped());
    }

    String asOutput(String before) {
        return before
                + "\n"
                + ReportExport.BEGIN
                + "\n"
                + base64()
                + "\n"
                + ReportExport.END
                + "\n";
    }

    private static void write(ByteArrayOutputStream out, String name, byte[] data, char type) {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(100, nameBytes.length));
        put(header, 100, "0000644");
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format("%011o", data.length));
        put(header, 136, "00000000000");
        header[156] = (byte) type;
        put(header, 257, "ustar");
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        put(header, 148, String.format("%06o", sum));
        header[154] = 0;
        header[155] = ' ';
        out.write(header, 0, 512);
        out.write(data, 0, data.length);
        int padding = (512 - data.length % 512) % 512;
        out.write(new byte[padding], 0, padding);
    }

    private static void put(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }
}
