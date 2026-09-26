package dev.rook.testkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rook.core.hash.Hashes;
import dev.rook.core.json.Json;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public final class FakeGitHub implements AutoCloseable {

    public record Request(
            String method, String path, String query, String authorization, String body) {}

    public record Pull(
            int number,
            String repository,
            String title,
            String body,
            String head,
            String base,
            String state) {}

    public record CheckRun(
            String repository, String headSha, String name, String conclusion, String summary) {}

    public record Comment(String repository, int number, String body) {}

    private record Repo(
            long id, long installation, String fullName, String defaultBranch, String cloneUrl) {}

    private record Failure(
            String method, String pathContains, int status, Map<String, String> headers) {}

    private static final Pattern PULLS = Pattern.compile("/repos/([^/]+/[^/]+)/pulls");
    private static final Pattern CHECKS = Pattern.compile("/repos/([^/]+/[^/]+)/check-runs");
    private static final Pattern COMMENTS =
            Pattern.compile("/repos/([^/]+/[^/]+)/issues/(\\d+)/comments");
    private static final Pattern REPO = Pattern.compile("/repos/([^/]+/[^/]+)");
    private static final Pattern TOKEN = Pattern.compile("/app/installations/(\\d+)/access_tokens");

    private final HttpServer server;
    private final Clock clock;
    private final List<Repo> repositories = new ArrayList<>();
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private final List<Pull> pulls = new ArrayList<>();
    private final List<CheckRun> checkRuns = new ArrayList<>();
    private final List<Comment> comments = new ArrayList<>();
    private final Deque<Failure> failures = new ArrayDeque<>();
    private final AtomicInteger issuedTokens = new AtomicInteger();
    private final Map<String, String> branchShas = new ConcurrentHashMap<>();

    public FakeGitHub(Clock clock) {
        this.clock = clock;
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

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized FakeGitHub addRepository(
            long installation, long id, String fullName, String defaultBranch, String cloneUrl) {
        repositories.add(new Repo(id, installation, fullName, defaultBranch, cloneUrl));
        return this;
    }

    public FakeGitHub setBranchSha(String repository, String branch, String sha) {
        branchShas.put(repository + "#" + branch, sha);
        return this;
    }

    public synchronized FakeGitHub failNext(
            String pathContains, int status, Map<String, String> headers) {
        return failNext(null, pathContains, status, headers);
    }

    public synchronized FakeGitHub failNext(
            String method, String pathContains, int status, Map<String, String> headers) {
        failures.add(new Failure(method, pathContains, status, Map.copyOf(headers)));
        return this;
    }

    public void expireTokens() {
        tokens.clear();
    }

    public int tokensIssued() {
        return issuedTokens.get();
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    public synchronized List<Pull> pulls() {
        return List.copyOf(pulls);
    }

    public synchronized List<CheckRun> checkRuns() {
        return List.copyOf(checkRuns);
    }

    public synchronized List<Comment> comments() {
        return List.copyOf(comments);
    }

    public synchronized void closePull(String repository, int number, boolean merged) {
        for (int i = 0; i < pulls.size(); i++) {
            Pull pull = pulls.get(i);
            if (pull.repository().equals(repository) && pull.number() == number) {
                pulls.set(
                        i,
                        new Pull(
                                number,
                                repository,
                                pull.title(),
                                pull.body(),
                                pull.head(),
                                pull.base(),
                                merged ? "merged" : "closed"));
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String method = exchange.getRequestMethod();
        Failure failure;
        synchronized (this) {
            requests.add(
                    new Request(method, path, query == null ? "" : query, authorization, body));
            failure = failures.peek();
            if (failure != null
                    && path.contains(failure.pathContains())
                    && (failure.method() == null || failure.method().equals(method))) {
                failures.poll();
            } else {
                failure = null;
            }
        }
        if (failure != null) {
            failure.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            reply(exchange, failure.status(), Map.of("message", "injected failure"));
            return;
        }
        try {
            route(exchange, method, path, query == null ? "" : query, authorization, body);
        } catch (RuntimeException e) {
            reply(exchange, 500, Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    private void route(
            HttpExchange exchange,
            String method,
            String path,
            String query,
            String authorization,
            String body)
            throws IOException {
        Matcher token = TOKEN.matcher(path);
        if (method.equals("POST") && token.matches()) {
            if (authorization == null || authorization.split("\\.").length != 3) {
                reply(exchange, 401, Map.of("message", "a JWT is required"));
                return;
            }
            long installation = Long.parseLong(token.group(1));
            String value = "ghs_fake_" + issuedTokens.incrementAndGet();
            tokens.put(value, installation);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("token", value);
            answer.put("expires_at", clock.instant().plus(Duration.ofHours(1)).toString());
            reply(exchange, 201, answer);
            return;
        }
        Long installation = installationOf(authorization);
        if (installation == null) {
            reply(exchange, 401, Map.of("message", "Bad credentials"));
            return;
        }
        if (path.equals("/installation/repositories") && method.equals("GET")) {
            reply(exchange, 200, repositoriesPage(installation, query));
            return;
        }
        Matcher comment = COMMENTS.matcher(path);
        if (method.equals("POST") && comment.matches()) {
            synchronized (this) {
                comments.add(
                        new Comment(
                                comment.group(1),
                                Integer.parseInt(comment.group(2)),
                                Json.tree(body).path("body").asString()));
            }
            reply(exchange, 201, Map.of("id", 1));
            return;
        }
        Matcher checks = CHECKS.matcher(path);
        if (method.equals("POST") && checks.matches()) {
            JsonNode json = Json.tree(body);
            synchronized (this) {
                checkRuns.add(
                        new CheckRun(
                                checks.group(1),
                                json.path("head_sha").asString(),
                                json.path("name").asString(),
                                json.path("conclusion").asString(),
                                json.path("output").path("summary").asString()));
            }
            reply(exchange, 201, Map.of("id", 1));
            return;
        }
        Matcher pull = PULLS.matcher(path);
        if (pull.matches()) {
            if (method.equals("POST")) {
                createPull(exchange, pull.group(1), Json.tree(body));
            } else {
                listPulls(exchange, pull.group(1), query);
            }
            return;
        }
        Matcher repo = REPO.matcher(path);
        if (method.equals("GET") && repo.matches()) {
            Repo found = find(repo.group(1));
            if (found == null || found.installation() != installation) {
                reply(exchange, 404, Map.of("message", "Not Found"));
            } else {
                reply(exchange, 200, repoJson(found));
            }
            return;
        }
        reply(exchange, 404, Map.of("message", "Not Found"));
    }

    private Long installationOf(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return tokens.get(authorization.substring(7));
    }

    private synchronized Repo find(String fullName) {
        return repositories.stream()
                .filter(r -> r.fullName().equals(fullName))
                .findFirst()
                .orElse(null);
    }

    private synchronized Map<String, Object> repositoriesPage(long installation, String query) {
        int perPage = intParam(query, "per_page", 30);
        int page = intParam(query, "page", 1);
        List<Map<String, Object>> mine =
                repositories.stream()
                        .filter(r -> r.installation() == installation)
                        .map(FakeGitHub::repoJson)
                        .toList();
        int from = Math.min(mine.size(), (page - 1) * perPage);
        int to = Math.min(mine.size(), from + perPage);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("total_count", mine.size());
        answer.put("repositories", mine.subList(from, to));
        return answer;
    }

    private static Map<String, Object> repoJson(Repo repo) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", repo.id());
        json.put("full_name", repo.fullName());
        json.put("default_branch", repo.defaultBranch());
        json.put("clone_url", repo.cloneUrl());
        return json;
    }

    private void createPull(HttpExchange exchange, String repository, JsonNode json)
            throws IOException {
        Pull created;
        synchronized (this) {
            String head = json.path("head").asString();
            boolean exists =
                    pulls.stream()
                            .anyMatch(
                                    p ->
                                            p.repository().equals(repository)
                                                    && p.head().equals(head)
                                                    && p.state().equals("open"));
            if (exists) {
                reply(
                        exchange,
                        422,
                        Map.of("message", "Validation Failed: a pull request already exists"));
                return;
            }
            int number =
                    (int) pulls.stream().filter(p -> p.repository().equals(repository)).count() + 1;
            created =
                    new Pull(
                            number,
                            repository,
                            json.path("title").asString(),
                            json.path("body").asString(),
                            head,
                            json.path("base").asString(),
                            "open");
            pulls.add(created);
        }
        reply(exchange, 201, pullJson(created));
    }

    private void listPulls(HttpExchange exchange, String repository, String query)
            throws IOException {
        String head = param(query, "head");
        String branch = head == null ? null : head.substring(head.indexOf(':') + 1);
        List<Map<String, Object>> matching;
        synchronized (this) {
            matching =
                    pulls.stream()
                            .filter(p -> p.repository().equals(repository))
                            .filter(p -> p.state().equals("open"))
                            .filter(p -> branch == null || p.head().equals(branch))
                            .map(this::pullJson)
                            .toList();
        }
        reply(exchange, 200, matching);
    }

    private Map<String, Object> pullJson(Pull pull) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("number", pull.number());
        json.put("html_url", "https://github.test/" + pull.repository() + "/pull/" + pull.number());
        json.put("state", pull.state());
        String sha =
                branchShas.getOrDefault(
                        pull.repository() + "#" + pull.head(),
                        Hashes.sha256Hex(pull.repository() + pull.head()).substring(0, 40));
        json.put("head", Map.of("sha", sha, "ref", pull.head()));
        return json;
    }

    private static int intParam(String query, String name, int fallback) {
        String value = param(query, name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void reply(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
