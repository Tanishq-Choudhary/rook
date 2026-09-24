package dev.rook.github.webhook;

import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import dev.rook.workflow.repo.RepositoryStore;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public class WebhookProcessor {

    public enum Outcome {
        HANDLED,
        IGNORED,
        DUPLICATE
    }

    public interface PushListener {

        void defaultBranchPushed(UUID repositoryId, String headSha);
    }

    private final RepositoryStore repositories;
    private final DeliveryLog deliveries;
    private final PullRequests pullRequests;
    private final PushListener pushes;

    public WebhookProcessor(
            RepositoryStore repositories,
            DeliveryLog deliveries,
            PullRequests pullRequests,
            PushListener pushes) {
        this.repositories = repositories;
        this.deliveries = deliveries;
        this.pullRequests = pullRequests;
        this.pushes = pushes;
    }

    public Outcome process(String deliveryId, String event, byte[] body) {
        if (deliveryId == null || deliveryId.isBlank() || deliveryId.length() > 100) {
            throw RookException.invalid("the delivery id is missing");
        }
        if (event == null || event.isBlank() || event.length() > 60) {
            throw RookException.invalid("the event name is missing");
        }
        JsonNode payload = Json.tree(new String(body, StandardCharsets.UTF_8));
        if (!payload.isObject()) {
            throw RookException.invalid("the payload is not a json object");
        }
        if (!deliveries.begin(deliveryId, event)) {
            return Outcome.DUPLICATE;
        }
        try {
            Outcome outcome = handle(event, payload);
            deliveries.complete(deliveryId, outcome.name());
            return outcome;
        } catch (RuntimeException e) {
            deliveries.abandon(deliveryId);
            throw e;
        }
    }

    private Outcome handle(String event, JsonNode payload) {
        return switch (event) {
            case "installation" -> installation(payload);
            case "installation_repositories" -> installationRepositories(payload);
            case "push" -> push(payload);
            case "pull_request" -> pullRequest(payload);
            default -> Outcome.IGNORED;
        };
    }

    private Outcome installation(JsonNode payload) {
        String action = text(payload, "action");
        JsonNode installation = required(payload, "installation");
        long id = installation.path("id").asLong(0);
        if (id == 0) {
            throw RookException.invalid("the installation id is missing");
        }
        switch (action) {
            case "created" -> {
                RepositoryStore.Installation stored =
                        repositories.upsertInstallation(id, login(installation));
                addAll(stored.id(), payload.path("repositories"));
            }
            case "deleted" -> repositories.setInstallationStatus(id, "REMOVED");
            case "suspend" -> repositories.setInstallationStatus(id, "SUSPENDED");
            case "unsuspend" -> repositories.setInstallationStatus(id, "ACTIVE");
            default -> {
                return Outcome.IGNORED;
            }
        }
        return Outcome.HANDLED;
    }

    private Outcome installationRepositories(JsonNode payload) {
        long id = required(payload, "installation").path("id").asLong(0);
        RepositoryStore.Installation installation =
                repositories
                        .findInstallation(id)
                        .orElseGet(
                                () ->
                                        repositories.upsertInstallation(
                                                id, login(payload.path("installation"))));
        addAll(installation.id(), payload.path("repositories_added"));
        for (JsonNode removed : payload.path("repositories_removed")) {
            repositories.disableRepository(removed.path("id").asLong());
        }
        return Outcome.HANDLED;
    }

    private Outcome push(JsonNode payload) {
        JsonNode repository = required(payload, "repository");
        String defaultBranch = repository.path("default_branch").asString("main");
        if (!("refs/heads/" + defaultBranch).equals(payload.path("ref").asString(""))) {
            return Outcome.IGNORED;
        }
        String after = payload.path("after").asString("");
        if (after.isBlank() || after.matches("0+")) {
            return Outcome.IGNORED;
        }
        return repositories
                .findByFullName(repository.path("full_name").asString(""))
                .filter(RepositoryStore.Repository::enabled)
                .map(
                        stored -> {
                            pushes.defaultBranchPushed(stored.id(), after);
                            return Outcome.HANDLED;
                        })
                .orElse(Outcome.IGNORED);
    }

    private Outcome pullRequest(JsonNode payload) {
        if (!"closed".equals(payload.path("action").asString(""))) {
            return Outcome.IGNORED;
        }
        JsonNode pull = required(payload, "pull_request");
        long repoId = required(payload, "repository").path("id").asLong(0);
        int number = pull.path("number").asInt(0);
        boolean merged = pull.path("merged").asBoolean(false);
        if (repoId == 0 || number == 0) {
            throw RookException.invalid("the pull request reference is incomplete");
        }
        return pullRequests.close(repoId, number, merged) ? Outcome.HANDLED : Outcome.IGNORED;
    }

    private void addAll(UUID installationId, JsonNode list) {
        for (JsonNode repository : list) {
            long id = repository.path("id").asLong(0);
            String fullName = repository.path("full_name").asString("");
            if (id != 0 && !fullName.isBlank()) {
                repositories.upsertRepository(
                        installationId,
                        id,
                        fullName,
                        repository.path("default_branch").asString("main"));
            }
        }
    }

    private static String login(JsonNode installation) {
        String login = installation.path("account").path("login").asString("");
        if (login.isBlank()) {
            throw RookException.invalid("the installation account is missing");
        }
        return login;
    }

    private static JsonNode required(JsonNode payload, String name) {
        JsonNode node = payload.get(name);
        if (node == null || !node.isObject()) {
            throw RookException.invalid("the payload has no " + name);
        }
        return node;
    }

    private static String text(JsonNode payload, String name) {
        return payload.path(name).asString("");
    }
}
