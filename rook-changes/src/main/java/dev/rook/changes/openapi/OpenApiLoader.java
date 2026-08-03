package dev.rook.changes.openapi;

import dev.rook.core.error.RookException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class OpenApiLoader {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";
    private static final int MAX_DEPTH = 48;

    private OpenApiLoader() {}

    public static OpenApiDocument load(byte[] content) {
        return load(new String(content, StandardCharsets.UTF_8));
    }

    public static OpenApiDocument load(String text) {
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (JacksonException e) {
            throw RookException.invalid("specification is not valid yaml or json", e);
        }
        if (root == null || !root.isObject()) {
            throw RookException.invalid("specification root must be an object");
        }
        String openapi = root.path("openapi").asString("");
        if (!openapi.startsWith("3.")) {
            throw RookException.invalid("only OpenAPI 3.x documents are supported");
        }
        return new Parse(root).document();
    }

    private static final class Parse {

        private final JsonNode root;
        private final JsonNode components;

        Parse(JsonNode root) {
            this.root = root;
            this.components = root.path("components");
        }

        OpenApiDocument document() {
            JsonNode info = root.path("info");
            Map<String, ApiSchema> schemas = new TreeMap<>();
            JsonNode schemaNodes = components.path("schemas");
            for (Map.Entry<String, JsonNode> entry : schemaNodes.properties()) {
                schemas.put(entry.getKey(), schema(entry.getValue(), 0, new HashSet<>()));
            }
            Map<String, ApiOperation> operations = new TreeMap<>();
            for (Map.Entry<String, JsonNode> path : root.path("paths").properties()) {
                JsonNode item = resolve(path.getValue());
                List<ApiParameter> shared = parameters(item.path("parameters"));
                for (Map.Entry<String, JsonNode> method : item.properties()) {
                    String verb = method.getKey().toLowerCase(Locale.ROOT);
                    if (!METHODS.contains(verb)) {
                        continue;
                    }
                    ApiOperation operation =
                            operation(
                                    verb.toUpperCase(Locale.ROOT),
                                    path.getKey(),
                                    method.getValue(),
                                    shared);
                    operations.put(operation.key(), operation);
                }
            }
            return new OpenApiDocument(
                    info.path("title").asString(""),
                    info.path("version").asString(""),
                    operations,
                    schemas);
        }

        private ApiOperation operation(
                String method, String path, JsonNode node, List<ApiParameter> shared) {
            Map<String, ApiParameter> merged = new LinkedHashMap<>();
            for (ApiParameter parameter : shared) {
                merged.put(parameter.key(), parameter);
            }
            for (ApiParameter parameter : parameters(node.path("parameters"))) {
                merged.put(parameter.key(), parameter);
            }
            JsonNode body = resolve(node.path("requestBody"));
            ApiSchema requestBody = mediaSchema(body.path("content"));
            Map<String, ApiSchema> responses = new TreeMap<>();
            for (Map.Entry<String, JsonNode> response : node.path("responses").properties()) {
                responses.put(
                        response.getKey(),
                        mediaSchema(resolve(response.getValue()).path("content")));
            }
            String operationId = node.path("operationId").asString(method + " " + path);
            String sunset = node.path("x-sunset").asString("");
            return new ApiOperation(
                    method,
                    path,
                    operationId,
                    node.path("deprecated").asBoolean(false),
                    sunset.isEmpty() ? null : sunset,
                    new ArrayList<>(merged.values()),
                    requestBody,
                    body.path("required").asBoolean(false),
                    responses);
        }

        private List<ApiParameter> parameters(JsonNode array) {
            List<ApiParameter> result = new ArrayList<>();
            if (!array.isArray()) {
                return result;
            }
            for (JsonNode raw : array) {
                JsonNode node = resolve(raw);
                String name = node.path("name").asString("");
                String location = node.path("in").asString("query");
                if (name.isEmpty()) {
                    continue;
                }
                boolean required =
                        "path".equals(location) || node.path("required").asBoolean(false);
                result.add(
                        new ApiParameter(
                                name,
                                location,
                                required,
                                node.path("deprecated").asBoolean(false),
                                schema(node.path("schema"), 0, new HashSet<>())));
            }
            return result;
        }

        private ApiSchema mediaSchema(JsonNode content) {
            if (!content.isObject()) {
                return ApiSchema.empty();
            }
            JsonNode json = content.path("application/json");
            if (json.isMissingNode()) {
                for (Map.Entry<String, JsonNode> entry : content.properties()) {
                    if (entry.getKey().contains("json")) {
                        json = entry.getValue();
                        break;
                    }
                }
            }
            if (json.isMissingNode()) {
                return ApiSchema.empty();
            }
            return schema(json.path("schema"), 0, new HashSet<>());
        }

        private ApiSchema schema(JsonNode node, int depth, Set<String> inProgress) {
            if (node == null || !node.isObject()) {
                return ApiSchema.empty();
            }
            if (depth > MAX_DEPTH) {
                throw RookException.invalid("schema nesting exceeds " + MAX_DEPTH + " levels");
            }
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isString()) {
                String target = ref.asString();
                if (target.startsWith(SCHEMA_PREFIX)) {
                    return ApiSchema.reference(target.substring(SCHEMA_PREFIX.length()));
                }
                return schema(resolve(node), depth + 1, inProgress);
            }
            Map<String, ApiSchema> properties = new TreeMap<>();
            Set<String> required = new TreeSet<>();
            JsonNode allOf = node.path("allOf");
            if (allOf.isArray()) {
                if (allOf.size() == 1 && !node.has("properties")) {
                    return schema(allOf.get(0), depth + 1, inProgress);
                }
                for (JsonNode member : allOf) {
                    mergeInto(member, properties, required, depth + 1, inProgress, false);
                }
            }
            for (String key : List.of("oneOf", "anyOf")) {
                JsonNode variants = node.path(key);
                if (variants.isArray()) {
                    for (JsonNode variant : variants) {
                        mergeInto(variant, properties, required, depth + 1, inProgress, true);
                    }
                }
            }
            for (Map.Entry<String, JsonNode> property : node.path("properties").properties()) {
                properties.put(
                        property.getKey(), schema(property.getValue(), depth + 1, inProgress));
            }
            for (JsonNode name : node.path("required")) {
                required.add(name.asString());
            }
            boolean nullable = node.path("nullable").asBoolean(false);
            String type = null;
            JsonNode typeNode = node.path("type");
            if (typeNode.isString()) {
                type = typeNode.asString();
            } else if (typeNode.isArray()) {
                for (JsonNode candidate : typeNode) {
                    if ("null".equals(candidate.asString())) {
                        nullable = true;
                    } else if (type == null) {
                        type = candidate.asString();
                    }
                }
            }
            JsonNode itemsNode = node.path("items");
            ApiSchema items =
                    itemsNode.isObject() ? schema(itemsNode, depth + 1, inProgress) : null;
            if (type == null) {
                if (!properties.isEmpty()) {
                    type = "object";
                } else if (items != null) {
                    type = "array";
                }
            }
            List<String> enumValues = new ArrayList<>();
            for (JsonNode value : node.path("enum")) {
                enumValues.add(value.asString());
            }
            JsonNode additional = node.path("additionalProperties");
            ApiSchema additionalProperties =
                    additional.isObject() ? schema(additional, depth + 1, inProgress) : null;
            return new ApiSchema(
                    null,
                    type,
                    node.path("format").asString(null),
                    nullable,
                    node.path("deprecated").asBoolean(false),
                    enumValues,
                    properties,
                    required,
                    items,
                    additionalProperties);
        }

        private void mergeInto(
                JsonNode member,
                Map<String, ApiSchema> properties,
                Set<String> required,
                int depth,
                Set<String> inProgress,
                boolean optionalOnly) {
            JsonNode resolved = member;
            JsonNode ref = member.get("$ref");
            if (ref != null && ref.isString() && ref.asString().startsWith(SCHEMA_PREFIX)) {
                String name = ref.asString().substring(SCHEMA_PREFIX.length());
                if (!inProgress.add(name)) {
                    return;
                }
                try {
                    resolved = components.path("schemas").path(name);
                    ApiSchema parsed = schema(resolved, depth + 1, inProgress);
                    properties.putAll(parsed.properties());
                    if (!optionalOnly) {
                        required.addAll(parsed.required());
                    }
                } finally {
                    inProgress.remove(name);
                }
                return;
            }
            ApiSchema parsed = schema(resolved, depth + 1, inProgress);
            properties.putAll(parsed.properties());
            if (!optionalOnly) {
                required.addAll(parsed.required());
            }
        }

        private JsonNode resolve(JsonNode node) {
            JsonNode current = node;
            for (int hops = 0; hops < 8; hops++) {
                JsonNode ref = current.get("$ref");
                if (ref == null || !ref.isString() || !ref.asString().startsWith("#/")) {
                    return current;
                }
                current = root.at(ref.asString().substring(1));
                if (current.isMissingNode()) {
                    return current;
                }
            }
            throw RookException.invalid("reference chain too deep");
        }
    }
}
