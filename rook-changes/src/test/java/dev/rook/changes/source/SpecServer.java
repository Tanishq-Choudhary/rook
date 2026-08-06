package dev.rook.changes.source;

import com.sun.net.httpserver.HttpServer;
import dev.rook.core.hash.Hashes;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SpecServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();

    public SpecServer() {
        try {
            server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext(
                "/spec",
                exchange -> {
                    requests.incrementAndGet();
                    int code = status.get();
                    String content = body.get();
                    String etag = "\"" + Hashes.sha256Hex(content).substring(0, 16) + "\"";
                    String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
                    if (code == 200 && etag.equals(ifNoneMatch)) {
                        exchange.getResponseHeaders().add("ETag", etag);
                        exchange.sendResponseHeaders(304, -1);
                        exchange.close();
                        return;
                    }
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("ETag", etag);
                    exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
                    if (bytes.length > 0) {
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(bytes);
                        }
                    }
                    exchange.close();
                });
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/spec");
    }

    public void serve(String content) {
        body.set(content);
        status.set(200);
    }

    public void fail(int code) {
        status.set(code);
    }

    public int requests() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
