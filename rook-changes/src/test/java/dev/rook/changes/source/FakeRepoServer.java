package dev.rook.changes.source;

import com.sun.net.httpserver.HttpServer;
import dev.rook.core.hash.Hashes;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeRepoServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    public FakeRepoServer() {
        try {
            server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
                    byte[] body = files.get(path);
                    int forced = statuses.getOrDefault(path, 0);
                    if (forced != 0) {
                        exchange.sendResponseHeaders(forced, -1);
                        exchange.close();
                        return;
                    }
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                        return;
                    }
                    String etag = "\"" + Hashes.sha256Hex(body).substring(0, 16) + "\"";
                    if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                        exchange.getResponseHeaders().add("ETag", etag);
                        exchange.sendResponseHeaders(304, -1);
                        exchange.close();
                        return;
                    }
                    exchange.getResponseHeaders().add("ETag", etag);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                    exchange.close();
                });
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public URI uri(String path) {
        return URI.create(base() + path);
    }

    public void put(String path, byte[] content) {
        files.put(path, content);
        statuses.remove(path);
    }

    public void putText(String path, String content) {
        put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    public void remove(String path) {
        files.remove(path);
    }

    public void forceStatus(String path, int status) {
        statuses.put(path, status);
    }

    public int hits(String path) {
        AtomicInteger counter = hits.get(path);
        return counter == null ? 0 : counter.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
