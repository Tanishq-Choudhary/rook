package dev.rook.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;

public final class SchemaValidator {

    private static final Set<String> KEYWORDS =
            Set.of(
                    "type",
                    "description",
                    "enum",
                    "properties",
                    "required",
                    "additionalProperties",
                    "minLength",
                    "maxLength",
                    "pattern",
                    "minimum",
                    "maximum",
                    "items",
                    "minItems",
                    "maxItems");

    private static final Set<String> TYPES =
            Set.of("string", "integer", "number", "boolean", "object", "array");

    private SchemaValidator() {}

    public static void requireSupported(JsonNode schema) {
        if (!schema.isObject()) {
            throw new IllegalArgumentException("a schema must be an object");
        }
        for (String keyword : schema.propertyNames()) {
            if (!KEYWORDS.contains(keyword)) {
                throw new IllegalArgumentException("unsupported schema keyword: " + keyword);
            }
        }
        String type = schema.path("type").asString("");
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("unsupported schema type: " + type);
        }
        if (schema.has("pattern")) {
            try {
                Pattern.compile(schema.path("pattern").asString());
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("invalid schema pattern", e);
            }
        }
        if (schema.path("properties").isObject()) {
            for (JsonNode property : schema.path("properties").values()) {
                requireSupported(property);
            }
        }
        if (schema.has("items")) {
            requireSupported(schema.path("items"));
        }
    }

    public static List<String> validate(JsonNode schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        check(schema, value, "$", errors);
        return errors;
    }

    private static void check(JsonNode schema, JsonNode value, String path, List<String> errors) {
        String type = schema.path("type").asString("");
        switch (type) {
            case "string" -> checkString(schema, value, path, errors);
            case "integer" -> {
                if (!value.isIntegralNumber()) {
                    errors.add(path + " must be an integer");
                    return;
                }
                checkRange(schema, value, path, errors);
            }
            case "number" -> {
                if (!value.isNumber()) {
                    errors.add(path + " must be a number");
                    return;
                }
                checkRange(schema, value, path, errors);
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    errors.add(path + " must be a boolean");
                }
            }
            case "array" -> checkArray(schema, value, path, errors);
            case "object" -> checkObject(schema, value, path, errors);
            default -> errors.add(path + " has a schema with an unknown type");
        }
        if (schema.has("enum") && !contains(schema.path("enum"), value)) {
            errors.add(path + " must be one of " + schema.path("enum"));
        }
    }

    private static boolean contains(JsonNode options, JsonNode value) {
        for (JsonNode option : options) {
            if (option.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static void checkString(
            JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isString()) {
            errors.add(path + " must be a string");
            return;
        }
        String text = value.asString();
        if (schema.has("minLength") && text.length() < schema.path("minLength").asInt()) {
            errors.add(
                    path
                            + " must have at least "
                            + schema.path("minLength").asInt()
                            + " characters");
        }
        if (schema.has("maxLength") && text.length() > schema.path("maxLength").asInt()) {
            errors.add(
                    path
                            + " must have at most "
                            + schema.path("maxLength").asInt()
                            + " characters");
        }
        if (schema.has("pattern")
                && !Pattern.compile(schema.path("pattern").asString()).matcher(text).find()) {
            errors.add(path + " does not match the required pattern");
        }
    }

    private static void checkRange(
            JsonNode schema, JsonNode value, String path, List<String> errors) {
        double number = value.asDouble();
        if (schema.has("minimum") && number < schema.path("minimum").asDouble()) {
            errors.add(path + " must be at least " + schema.path("minimum").asString());
        }
        if (schema.has("maximum") && number > schema.path("maximum").asDouble()) {
            errors.add(path + " must be at most " + schema.path("maximum").asString());
        }
    }

    private static void checkArray(
            JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isArray()) {
            errors.add(path + " must be an array");
            return;
        }
        if (schema.has("minItems") && value.size() < schema.path("minItems").asInt()) {
            errors.add(path + " must have at least " + schema.path("minItems").asInt() + " items");
        }
        if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) {
            errors.add(path + " must have at most " + schema.path("maxItems").asInt() + " items");
        }
        if (schema.has("items")) {
            for (int i = 0; i < value.size(); i++) {
                check(schema.path("items"), value.get(i), path + "[" + i + "]", errors);
            }
        }
    }

    private static void checkObject(
            JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isObject()) {
            errors.add(path + " must be an object");
            return;
        }
        JsonNode properties = schema.path("properties");
        for (JsonNode required : schema.path("required")) {
            if (!value.has(required.asString())) {
                errors.add(path + "." + required.asString() + " is required");
            }
        }
        boolean closed = !schema.path("additionalProperties").asBoolean(true);
        for (String name : value.propertyNames()) {
            JsonNode propertySchema = properties.path(name);
            if (propertySchema.isMissingNode()) {
                if (closed) {
                    errors.add(path + "." + name + " is not an allowed property");
                }
                continue;
            }
            check(propertySchema, value.get(name), path + "." + name, errors);
        }
    }
}
