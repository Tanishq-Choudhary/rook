package dev.rook.server.changes;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

final class FakeUpstream implements AutoCloseable {

    static final String V1 =
            "openapi: 3.0.0\ninfo: {title: t, version: '1'}\npaths:\n  /a: {get: {operationId: getA, responses: {'200': {description: ok}}}}\n  /b: {get: {operationId: getB, responses: {'200': {description: ok}}}}\n";
    static final String V2 =
            "openapi: 3.0.0\ninfo: {title: t, version: '2'}\npaths:\n  /a: {get: {operationId: getA, responses: {'200': {description: ok}}}}\n";

    private final HttpServer server;
    private final AtomicReference<String> spec = new AtomicReference<>(V1);

    FakeUpstream() {
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
                    byte[] body = spec.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/spec";
    }

    void serve(String content) {
        spec.set(content);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
