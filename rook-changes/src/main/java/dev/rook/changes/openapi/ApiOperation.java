package dev.rook.changes.openapi;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ApiOperation(
        String method,
        String path,
        String operationId,
        boolean deprecated,
        String sunset,
        List<ApiParameter> parameters,
        ApiSchema requestBody,
        boolean requestBodyRequired,
        Map<String, ApiSchema> responses) {

    public ApiOperation {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        requestBody = requestBody == null ? ApiSchema.empty() : requestBody;
        responses =
                responses == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new TreeMap<>(responses));
    }

    public String key() {
        return method + " " + path;
    }

    public Map<String, ApiParameter> parametersByKey() {
        Map<String, ApiParameter> byKey = new TreeMap<>();
        for (ApiParameter parameter : parameters) {
            byKey.put(parameter.key(), parameter);
        }
        return byKey;
    }
}
