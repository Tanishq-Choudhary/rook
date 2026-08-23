package dev.rook.agent.tools;

import dev.rook.core.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class Schemas {

    private Schemas() {}

    public static ObjectNode string(String description) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    public static ObjectNode string(String description, int minLength, int maxLength) {
        ObjectNode node = string(description);
        node.put("minLength", minLength);
        node.put("maxLength", maxLength);
        return node;
    }

    public static ObjectNode integer(String description, long minimum, long maximum) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        node.put("minimum", minimum);
        node.put("maximum", maximum);
        return node;
    }

    public static ObjectNode number(String description, double minimum, double maximum) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("type", "number");
        node.put("description", description);
        node.put("minimum", minimum);
        node.put("maximum", maximum);
        return node;
    }

    public static ObjectNode bool(String description) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("type", "boolean");
        node.put("description", description);
        return node;
    }

    public static ObjectNode enumOf(String description, List<String> values) {
        ObjectNode node = string(description);
        ArrayNode array = node.putArray("enum");
        values.forEach(array::add);
        return node;
    }

    public static ObjectNode array(String description, ObjectNode items, int maxItems) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("type", "array");
        node.put("description", description);
        node.set("items", items);
        node.put("maxItems", maxItems);
        return node;
    }

    public static ObjectNode object(Map<String, ObjectNode> properties, List<String> required) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("type", "object");
        ObjectNode props = node.putObject("properties");
        new LinkedHashMap<>(properties).forEach(props::set);
        ArrayNode req = node.putArray("required");
        required.forEach(req::add);
        node.put("additionalProperties", false);
        return node;
    }
}
