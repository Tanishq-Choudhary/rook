package dev.rook.agent.model.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class FakeApi implements AutoCloseable {

    record Received(String path, Map<String, String> headers, String body) {}

    private record Reply(int status, String body, long delayMillis) {}

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Received> received = new ArrayList<>();

    FakeApi() {
        try {
            server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    synchronized FakeApi reply(int status, String body) {
        replies.add(new Reply(status, body, 0));
        return this;
    }

    synchronized FakeApi replySlowly(String body, long delayMillis) {
        replies.add(new Reply(200, body, delayMillis));
        return this;
    }

    synchronized List<Received> received() {
        return List.copyOf(received);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders()
                .forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        Reply reply;
        synchronized (this) {
            received.add(
                    new Received(
                            exchange.getRequestURI().getPath(),
                            headers,
                            new String(body, StandardCharsets.UTF_8)));
            reply = replies.poll();
        }
        if (reply == null) {
            reply = new Reply(500, "no reply queued", 0);
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(reply.status(), out.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
