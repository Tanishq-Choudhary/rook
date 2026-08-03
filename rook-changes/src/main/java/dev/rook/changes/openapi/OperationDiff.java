package dev.rook.changes.openapi;

import dev.rook.changes.diff.DiffContext;
import dev.rook.changes.diff.RenameMatcher;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

final class OperationDiff {

    private final DiffContext ctx;
    private final List<ChangeEvent> events = new ArrayList<>();

    private OperationDiff(DiffContext ctx) {
        this.ctx = ctx;
    }

    static List<ChangeEvent> run(DiffContext ctx, OpenApiDocument before, OpenApiDocument after) {
        OperationDiff diff = new OperationDiff(ctx);
        diff.compare(before, after);
        return diff.events;
    }

    private void compare(OpenApiDocument before, OpenApiDocument after) {
        Map<String, ApiOperation> old = before.operations();
        Map<String, ApiOperation> now = after.operations();
        TreeSet<String> removed = new TreeSet<>(old.keySet());
        removed.removeAll(now.keySet());
        TreeSet<String> added = new TreeSet<>(now.keySet());
        added.removeAll(old.keySet());

        Map<String, String> addedByOperationId = new HashMap<>();
        Map<String, Integer> operationIdCounts = new HashMap<>();
        for (String key : added) {
            ApiOperation operation = now.get(key);
            if (!operation.operationId().equals(key)) {
                addedByOperationId.put(operation.operationId(), key);
                operationIdCounts.merge(operation.operationId(), 1, Integer::sum);
            }
        }

        Map<String, String> moved = new LinkedHashMap<>();
        for (String key : removed) {
            ApiOperation operation = old.get(key);
            String id = operation.operationId();
            if (!id.equals(key)
                    && addedByOperationId.containsKey(id)
                    && operationIdCounts.get(id) == 1) {
                moved.put(key, addedByOperationId.get(id));
            }
        }

        for (String key : removed) {
            ApiOperation operation = old.get(key);
            Map<String, String> attributes = new TreeMap<>();
            attributes.put(Attr.OPERATION_ID, operation.operationId());
            String summary = key + " removed";
            if (moved.containsKey(key)) {
                attributes.put(Attr.REPLACEMENT, moved.get(key));
                attributes.put(Attr.CONFIDENCE, "0.95");
                summary = key + " moved to " + moved.get(key);
            }
            events.add(
                    ctx.event(
                            ChangeKind.OPERATION_REMOVED,
                            SymbolRef.operation(operation.method(), operation.path()),
                            attributes,
                            summary));
            if (moved.containsKey(key)) {
                compareOperation(operation, now.get(moved.get(key)));
            }
        }
        for (String key : added) {
            if (moved.containsValue(key)) {
                continue;
            }
            ApiOperation operation = now.get(key);
            events.add(
                    ctx.event(
                            ChangeKind.OPERATION_ADDED,
                            SymbolRef.operation(operation.method(), operation.path()),
                            Map.of(Attr.OPERATION_ID, operation.operationId()),
                            key + " added"));
        }
        for (Map.Entry<String, ApiOperation> entry : old.entrySet()) {
            ApiOperation next = now.get(entry.getKey());
            if (next != null) {
                compareOperation(entry.getValue(), next);
            }
        }
    }

    private void compareOperation(ApiOperation before, ApiOperation after) {
        SymbolRef operation = SymbolRef.operation(before.method(), before.path());
        String key = before.key();
        if (!before.deprecated() && after.deprecated()) {
            Map<String, String> attributes = new TreeMap<>();
            attributes.put(Attr.OPERATION_ID, before.operationId());
            if (after.sunset() != null) {
                attributes.put(Attr.SUNSET, after.sunset());
            }
            events.add(
                    ctx.event(
                            ChangeKind.OPERATION_DEPRECATED,
                            operation,
                            attributes,
                            key
                                    + " deprecated"
                                    + (after.sunset() == null
                                            ? ""
                                            : ", sunset " + after.sunset())));
        }
        compareParameters(key, before, after);
        compareBody(key, before, after);
        compareResponses(key, before, after);
    }

    private void compareParameters(String key, ApiOperation before, ApiOperation after) {
        Map<String, ApiParameter> old = before.parametersByKey();
        Map<String, ApiParameter> now = after.parametersByKey();
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (String name : old.keySet()) {
            if (!now.containsKey(name)) {
                removed.add(name);
            }
        }
        for (String name : now.keySet()) {
            if (!old.containsKey(name)) {
                added.add(name);
            }
        }
        Map<String, String> removedTypes = new HashMap<>();
        Map<String, String> addedTypes = new HashMap<>();
        removed.forEach(
                n ->
                        removedTypes.put(
                                n, old.get(n).location() + "|" + old.get(n).schema().typeLabel()));
        List<String> optionalAdded = new ArrayList<>();
        for (String name : added) {
            addedTypes.put(
                    name, now.get(name).location() + "|" + now.get(name).schema().typeLabel());
            if (!now.get(name).required()) {
                optionalAdded.add(name);
            }
        }
        Map<String, RenameMatcher.Rename> renames = new HashMap<>();
        for (RenameMatcher.Rename rename :
                RenameMatcher.match(removed, optionalAdded, removedTypes, addedTypes)) {
            renames.put(rename.from(), rename);
        }
        for (String name : removed) {
            ApiParameter parameter = old.get(name);
            Map<String, String> attributes = new TreeMap<>();
            attributes.put(Attr.LOCATION, parameter.location());
            attributes.put(Attr.NAME, parameter.name());
            String summary = key + " parameter " + name + " removed";
            RenameMatcher.Rename rename = renames.get(name);
            if (rename != null) {
                attributes.put(Attr.REPLACEMENT, now.get(rename.to()).name());
                attributes.put(Attr.CONFIDENCE, Double.toString(rename.confidence()));
                summary =
                        key
                                + " parameter "
                                + parameter.name()
                                + " replaced by "
                                + now.get(rename.to()).name();
            }
            events.add(
                    ctx.event(
                            ChangeKind.PARAMETER_REMOVED,
                            SymbolRef.parameter(key, parameter.location(), parameter.name()),
                            attributes,
                            summary));
        }
        for (String name : added) {
            ApiParameter parameter = now.get(name);
            ChangeKind kind =
                    parameter.required()
                            ? ChangeKind.PARAMETER_REQUIRED_ADDED
                            : ChangeKind.PARAMETER_OPTIONAL_ADDED;
            events.add(
                    ctx.event(
                            kind,
                            SymbolRef.parameter(key, parameter.location(), parameter.name()),
                            Map.of(
                                    Attr.LOCATION, parameter.location(),
                                    Attr.NAME, parameter.name(),
                                    Attr.TO, parameter.schema().typeLabel()),
                            key
                                    + " parameter "
                                    + name
                                    + (parameter.required() ? " is now required" : " added")));
        }
        for (Map.Entry<String, ApiParameter> entry : old.entrySet()) {
            ApiParameter next = now.get(entry.getKey());
            if (next == null) {
                continue;
            }
            ApiParameter previous = entry.getValue();
            SymbolRef subject = SymbolRef.parameter(key, previous.location(), previous.name());
            if (!previous.required() && next.required()) {
                events.add(
                        ctx.event(
                                ChangeKind.PARAMETER_MADE_REQUIRED,
                                subject,
                                Map.of(
                                        Attr.LOCATION,
                                        previous.location(),
                                        Attr.NAME,
                                        previous.name()),
                                key + " parameter " + entry.getKey() + " is now required"));
            }
            String oldLabel = previous.schema().typeLabel();
            String newLabel = next.schema().typeLabel();
            if (!oldLabel.equals(newLabel)) {
                events.add(
                        ctx.event(
                                ChangeKind.PARAMETER_TYPE_CHANGED,
                                subject,
                                Map.of(
                                        Attr.LOCATION,
                                        previous.location(),
                                        Attr.NAME,
                                        previous.name(),
                                        Attr.FROM,
                                        oldLabel,
                                        Attr.TO,
                                        newLabel),
                                key
                                        + " parameter "
                                        + entry.getKey()
                                        + " changed from "
                                        + oldLabel
                                        + " to "
                                        + newLabel));
            }
        }
    }

    private void compareBody(String key, ApiOperation before, ApiOperation after) {
        ApiSchema old = before.requestBody();
        ApiSchema now = after.requestBody();
        SymbolRef body = SymbolRef.parameter(key, "body", "body");
        if (old.isEmpty() && !now.isEmpty()) {
            if (after.requestBodyRequired()) {
                events.add(
                        ctx.event(
                                ChangeKind.PARAMETER_REQUIRED_ADDED,
                                body,
                                Map.of(
                                        Attr.LOCATION,
                                        "body",
                                        Attr.NAME,
                                        "body",
                                        Attr.TO,
                                        now.typeLabel()),
                                key + " now requires a request body"));
            }
            return;
        }
        if (!old.isEmpty() && now.isEmpty()) {
            events.add(
                    ctx.event(
                            ChangeKind.PARAMETER_REMOVED,
                            body,
                            Map.of(Attr.LOCATION, "body", Attr.NAME, "body"),
                            key + " no longer accepts a request body"));
            return;
        }
        if (!old.isEmpty() && !old.typeLabel().equals(now.typeLabel())) {
            events.add(
                    ctx.event(
                            ChangeKind.PARAMETER_TYPE_CHANGED,
                            body,
                            Map.of(
                                    Attr.LOCATION,
                                    "body",
                                    Attr.NAME,
                                    "body",
                                    Attr.FROM,
                                    old.typeLabel(),
                                    Attr.TO,
                                    now.typeLabel()),
                            key
                                    + " request body changed from "
                                    + old.typeLabel()
                                    + " to "
                                    + now.typeLabel()));
        }
        if (!before.requestBodyRequired() && after.requestBodyRequired() && !old.isEmpty()) {
            events.add(
                    ctx.event(
                            ChangeKind.PARAMETER_MADE_REQUIRED,
                            body,
                            Map.of(Attr.LOCATION, "body", Attr.NAME, "body"),
                            key + " request body is now required"));
        }
    }

    private void compareResponses(String key, ApiOperation before, ApiOperation after) {
        for (Map.Entry<String, ApiSchema> entry : before.responses().entrySet()) {
            String status = entry.getKey();
            ApiSchema next = after.responses().get(status);
            if (next == null) {
                if (status.startsWith("2")) {
                    events.add(
                            ctx.event(
                                    ChangeKind.RESPONSE_STATUS_REMOVED,
                                    SymbolRef.operation(before.method(), before.path()),
                                    Map.of(Attr.STATUS, status),
                                    key + " no longer responds with " + status));
                }
                continue;
            }
            if (!status.startsWith("2")) {
                continue;
            }
            ApiSchema previous = entry.getValue();
            if (!previous.isEmpty()
                    && !next.isEmpty()
                    && !previous.typeLabel().equals(next.typeLabel())) {
                events.add(
                        ctx.event(
                                ChangeKind.RESPONSE_FIELD_TYPE_CHANGED,
                                SymbolRef.schemaField(key + " response " + status, "body"),
                                Map.of(
                                        Attr.STATUS, status,
                                        Attr.FROM, previous.typeLabel(),
                                        Attr.TO, next.typeLabel()),
                                key
                                        + " response "
                                        + status
                                        + " changed from "
                                        + previous.typeLabel()
                                        + " to "
                                        + next.typeLabel()));
            }
        }
    }
}
