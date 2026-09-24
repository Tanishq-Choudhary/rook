package dev.rook.github.api;

import dev.rook.core.json.Json;
import dev.rook.github.auth.InstallationTokens;
import dev.rook.github.auth.TokenSource;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

public class GitHubClient implements InstallationTokens.Issuer {

    public record Repo(long id, String fullName, String defaultBranch, String cloneUrl) {}

    public record PullRequest(int number, String url, String state, String headSha) {}

    public interface Sleeper {

        void sleep(long millis) throws InterruptedException;
    }

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private static final int MAX_ATTEMPTS = 4;

    private final URI base;
    private final HttpClient http;
    private final TokenSource tokens;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Duration maxWait;

    public GitHubClient(
            String baseUrl,
            HttpClient http,
            TokenSource tokens,
            Sleeper sleeper,
            Clock clock,
            Duration maxWait) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.http = http;
        this.tokens = tokens;
        this.sleeper = sleeper;
        this.clock = clock;
        this.maxWait = maxWait;
    }

    @Override
    public InstallationTokens.Token issue(String appJwt, long installationId) {
        JsonNode body =
                send(
                        "POST",
                        "app/installations/" + installationId + "/access_tokens",
                        "Bearer " + appJwt,
                        installationId,
                        null,
                        false);
        return new InstallationTokens.Token(
                body.path("token").asString(), Instant.parse(body.path("expires_at").asString()));
    }

    public List<Repo> installationRepositories(long installationId) {
        List<Repo> repositories = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode body =
                    call(
                            "GET",
                            "installation/repositories?per_page=" + PAGE_SIZE + "&page=" + page,
                            installationId,
                            null);
            JsonNode list = body.path("repositories");
            list.forEach(node -> repositories.add(repo(node)));
            if (list.size() < PAGE_SIZE) {
                break;
            }
        }
        return repositories;
    }

    public Repo repository(long installationId, String fullName) {
        return repo(call("GET", "repos/" + fullName, installationId, null));
    }

    public PullRequest createPullRequest(
            long installationId,
            String fullName,
            String title,
            String body,
            String head,
            String baseBranch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("head", head);
        payload.put("base", baseBranch);
        payload.put("maintainer_can_modify", false);
        return pull(call("POST", "repos/" + fullName + "/pulls", installationId, payload));
    }

    public Optional<PullRequest> findOpenPullRequest(
            long installationId, String fullName, String branch) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        String head = URLEncoder.encode(owner + ":" + branch, StandardCharsets.UTF_8);
        JsonNode list =
                call(
                        "GET",
                        "repos/" + fullName + "/pulls?state=open&head=" + head,
                        installationId,
                        null);
        return list.isArray() && !list.isEmpty()
                ? Optional.of(pull(list.get(0)))
                : Optional.empty();
    }

    public void createCheckRun(
            long installationId,
            String fullName,
            String headSha,
            String name,
            String conclusion,
            String title,
            String summary) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("title", title);
        output.put("summary", summary);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("head_sha", headSha);
        payload.put("status", "completed");
        payload.put("conclusion", conclusion);
        payload.put("output", output);
        call("POST", "repos/" + fullName + "/check-runs", installationId, payload);
    }

    public void comment(long installationId, String fullName, int number, String body) {
        call(
                "POST",
                "repos/" + fullName + "/issues/" + number + "/comments",
                installationId,
                Map.of("body", body));
    }

    private JsonNode call(String method, String path, long installationId, Object body) {
        return send(method, path, null, installationId, body, true);
    }

    private JsonNode send(
            String method,
            String path,
            String fixedAuthorization,
            long installationId,
            Object body,
            boolean installationAuth) {
        boolean refreshedToken = false;
        for (int attempt = 1; ; attempt++) {
            String authorization =
                    installationAuth
                            ? "Bearer " + tokens.tokenFor(installationId)
                            : fixedAuthorization;
            HttpResponse<String> response = execute(method, path, authorization, body);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                String text = response.body();
                return text == null || text.isBlank() ? Json.tree(Map.of()) : Json.tree(text);
            }
            if (status == 401 && installationAuth && !refreshedToken) {
                tokens.forget(installationId);
                refreshedToken = true;
                continue;
            }
            long wait = waitMillis(response, attempt);
            boolean retryable = wait >= 0 && attempt < MAX_ATTEMPTS;
            if (retryable && wait <= maxWait.toMillis()) {
                pause(wait);
                continue;
            }
            throw new GitHubException(status, describe(method, path, response), Math.max(wait, 0));
        }
    }

    private long waitMillis(HttpResponse<String> response, int attempt) {
        int status = response.statusCode();
        Optional<String> retryAfter = response.headers().firstValue("retry-after");
        boolean limited =
                status == 429
                        || (status == 403
                                && (retryAfter.isPresent()
                                        || "0"
                                                .equals(
                                                        response.headers()
                                                                .firstValue("x-ratelimit-remaining")
                                                                .orElse(""))));
        if (limited) {
            if (retryAfter.isPresent()) {
                try {
                    return Long.parseLong(retryAfter.get().strip()) * 1000;
                } catch (NumberFormatException e) {
                    return 1000;
                }
            }
            Optional<String> reset = response.headers().firstValue("x-ratelimit-reset");
            if (reset.isPresent()) {
                try {
                    long epoch = Long.parseLong(reset.get().strip());
                    return Math.max(1000, epoch * 1000 - clock.millis());
                } catch (NumberFormatException e) {
                    return 1000;
                }
            }
            return 1000L * attempt;
        }
        if (status >= 500) {
            return 250L * attempt;
        }
        return -1;
    }

    private HttpResponse<String> execute(
            String method, String path, String authorization, Object body) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(base.resolve(path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", authorization)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "rook");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GitHubException(503, "github could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubException(503, "the github call was interrupted");
        }
    }

    private void pause(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubException(503, "interrupted while waiting for github");
        }
    }

    private static String describe(String method, String path, HttpResponse<String> response) {
        String message = "";
        try {
            message = Json.tree(response.body()).path("message").asString("");
        } catch (RuntimeException e) {
            message = "";
        }
        String shortPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        return method
                + " "
                + shortPath
                + " answered "
                + response.statusCode()
                + (message.isEmpty() ? "" : ": " + message);
    }

    private static Repo repo(JsonNode node) {
        return new Repo(
                node.path("id").asLong(),
                node.path("full_name").asString(),
                node.path("default_branch").asString("main"),
                node.path("clone_url").asString(""));
    }

    private static PullRequest pull(JsonNode node) {
        return new PullRequest(
                node.path("number").asInt(),
                node.path("html_url").asString(""),
                node.path("state").asString("open"),
                node.path("head").path("sha").asString(""));
    }
}
