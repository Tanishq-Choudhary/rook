package dev.rook.changes.openapi;

import dev.rook.changes.diff.DiffContext;
import dev.rook.changes.diff.RenameMatcher;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class SchemaDiff {

    private final DiffContext ctx;
    private final SchemaUsage usage;
    private final List<ChangeEvent> events = new ArrayList<>();

    private SchemaDiff(DiffContext ctx, SchemaUsage usage) {
        this.ctx = ctx;
        this.usage = usage;
    }

    static List<ChangeEvent> run(DiffContext ctx, OpenApiDocument before, OpenApiDocument after) {
        SchemaDiff diff = new SchemaDiff(ctx, SchemaUsage.of(before, after));
        diff.compare(before, after);
        return diff.events;
    }

    private void compare(OpenApiDocument before, OpenApiDocument after) {
        for (Map.Entry<String, ApiSchema> entry : before.schemas().entrySet()) {
            ApiSchema next = after.schemas().get(entry.getKey());
            if (next != null) {
                compareSchema(
                        entry.getKey(),
                        "",
                        entry.getValue(),
                        next,
                        usage.directions(entry.getKey()));
            }
        }
        for (Map.Entry<String, ApiOperation> entry : before.operations().entrySet()) {
            ApiOperation next = after.operations().get(entry.getKey());
            if (next == null) {
                continue;
            }
            ApiOperation previous = entry.getValue();
            compareInline(
                    previous.key() + " request",
                    previous.requestBody(),
                    next.requestBody(),
                    Set.of(SchemaUsage.Direction.REQUEST));
            for (Map.Entry<String, ApiSchema> response : previous.responses().entrySet()) {
                ApiSchema nextResponse = next.responses().get(response.getKey());
                if (nextResponse != null && response.getKey().startsWith("2")) {
                    compareInline(
                            previous.key() + " response " + response.getKey(),
                            response.getValue(),
                            nextResponse,
                            Set.of(SchemaUsage.Direction.RESPONSE));
                }
            }
        }
    }

    private void compareInline(
            String name, ApiSchema before, ApiSchema after, Set<SchemaUsage.Direction> directions) {
        if (!before.isRef() && !after.isRef() && before.isObject() && after.isObject()) {
            compareSchema(name, "", before, after, directions);
        }
    }

    private void compareSchema(
            String schemaName,
            String prefix,
            ApiSchema before,
            ApiSchema after,
            Set<SchemaUsage.Direction> directions) {
        boolean request = directions.contains(SchemaUsage.Direction.REQUEST);
        boolean response = directions.contains(SchemaUsage.Direction.RESPONSE);
        String direction = request && response ? "both" : request ? "request" : "response";

        if (prefix.isEmpty() && !before.enumValues().isEmpty()) {
            compareEnum(schemaName, "", before, after, direction);
        }

        Map<String, ApiSchema> old = before.properties();
        Map<String, ApiSchema> now = after.properties();
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
        removed.forEach(n -> removedTypes.put(n, old.get(n).typeLabel()));
        added.forEach(n -> addedTypes.put(n, now.get(n).typeLabel()));
        Map<String, RenameMatcher.Rename> renames = new HashMap<>();
        Set<String> renamedTargets = new TreeSet<>();
        for (RenameMatcher.Rename rename :
                RenameMatcher.match(removed, added, removedTypes, addedTypes)) {
            renames.put(rename.from(), rename);
            renamedTargets.add(rename.to());
        }

        for (String name : removed) {
            String path = prefix + name;
            RenameMatcher.Rename rename = renames.get(name);
            if (rename != null) {
                events.add(
                        ctx.event(
                                ChangeKind.FIELD_RENAMED,
                                SymbolRef.schemaField(schemaName, path),
                                attributes(
                                        schemaName,
                                        path,
                                        direction,
                                        Attr.REPLACEMENT,
                                        prefix + rename.to(),
                                        Attr.CONFIDENCE,
                                        Double.toString(rename.confidence())),
                                schemaName + "." + path + " renamed to " + prefix + rename.to()));
                continue;
            }
            ChangeKind kind =
                    response ? ChangeKind.RESPONSE_FIELD_REMOVED : ChangeKind.REQUEST_FIELD_REMOVED;
            events.add(
                    ctx.event(
                            kind,
                            SymbolRef.schemaField(schemaName, path),
                            attributes(schemaName, path, direction),
                            schemaName + "." + path + " removed"));
        }

        for (String name : added) {
            if (renamedTargets.contains(name)) {
                continue;
            }
            String path = prefix + name;
            boolean required = after.isRequired(name);
            ChangeKind kind;
            if (request && required) {
                kind = ChangeKind.REQUEST_FIELD_REQUIRED_ADDED;
            } else if (request && !response) {
                kind = ChangeKind.REQUEST_FIELD_OPTIONAL_ADDED;
            } else {
                kind = ChangeKind.RESPONSE_FIELD_ADDED;
            }
            events.add(
                    ctx.event(
                            kind,
                            SymbolRef.schemaField(schemaName, path),
                            attributes(
                                    schemaName,
                                    path,
                                    direction,
                                    Attr.TO,
                                    now.get(name).typeLabel()),
                            schemaName + "." + path + " added" + (required ? " as required" : "")));
        }

        for (Map.Entry<String, ApiSchema> entry : old.entrySet()) {
            String name = entry.getKey();
            ApiSchema next = now.get(name);
            if (next == null) {
                continue;
            }
            ApiSchema previous = entry.getValue();
            String path = prefix + name;
            SymbolRef subject = SymbolRef.schemaField(schemaName, path);

            if (!previous.typeLabel().equals(next.typeLabel())) {
                ChangeKind kind =
                        response
                                ? ChangeKind.RESPONSE_FIELD_TYPE_CHANGED
                                : ChangeKind.REQUEST_FIELD_TYPE_CHANGED;
                events.add(
                        ctx.event(
                                kind,
                                subject,
                                attributes(
                                        schemaName,
                                        path,
                                        direction,
                                        Attr.FROM,
                                        previous.typeLabel(),
                                        Attr.TO,
                                        next.typeLabel()),
                                schemaName
                                        + "."
                                        + path
                                        + " changed from "
                                        + previous.typeLabel()
                                        + " to "
                                        + next.typeLabel()));
            }
            boolean wasRequired = before.isRequired(name);
            boolean isRequired = after.isRequired(name);
            if (request && !wasRequired && isRequired) {
                events.add(
                        ctx.event(
                                ChangeKind.REQUEST_FIELD_MADE_REQUIRED,
                                subject,
                                attributes(schemaName, path, direction),
                                schemaName + "." + path + " is now required"));
            }
            boolean becameOptional = wasRequired && !isRequired;
            boolean becameNullable = !previous.nullable() && next.nullable();
            if (response && (becameOptional || becameNullable)) {
                events.add(
                        ctx.event(
                                ChangeKind.RESPONSE_FIELD_MADE_OPTIONAL,
                                subject,
                                attributes(schemaName, path, direction),
                                schemaName + "." + path + " may now be absent or null"));
            }
            if (!previous.deprecated() && next.deprecated()) {
                events.add(
                        ctx.event(
                                ChangeKind.FIELD_DEPRECATED,
                                subject,
                                attributes(schemaName, path, direction),
                                schemaName + "." + path + " deprecated"));
            }
            if (!previous.enumValues().isEmpty()) {
                compareEnum(schemaName, path, previous, next, direction);
            }
            if (previous.typeLabel().equals(next.typeLabel())) {
                if (!previous.isRef() && previous.isObject() && next.isObject()) {
                    compareSchema(schemaName, path + ".", previous, next, directions);
                } else if ("array".equals(previous.type())
                        && previous.items() != null
                        && next.items() != null
                        && !previous.items().isRef()
                        && previous.items().isObject()
                        && next.items().isObject()) {
                    compareSchema(
                            schemaName, path + "[].", previous.items(), next.items(), directions);
                }
            }
        }
    }

    private void compareEnum(
            String schemaName, String path, ApiSchema before, ApiSchema after, String direction) {
        Set<String> removed = new TreeSet<>(before.enumValues());
        removed.removeAll(after.enumValues());
        Set<String> added = new TreeSet<>(after.enumValues());
        added.removeAll(before.enumValues());
        String owner = path.isEmpty() ? schemaName : schemaName + "." + path;
        Map<String, String> removedTypes = new HashMap<>();
        Map<String, String> addedTypes = new HashMap<>();
        removed.forEach(v -> removedTypes.put(v, "enum"));
        added.forEach(v -> addedTypes.put(v, "enum"));
        Map<String, RenameMatcher.Rename> renames = new HashMap<>();
        for (RenameMatcher.Rename rename :
                RenameMatcher.match(
                        new ArrayList<>(removed),
                        new ArrayList<>(added),
                        removedTypes,
                        addedTypes)) {
            renames.put(rename.from(), rename);
        }
        for (String value : removed) {
            Map<String, String> attributes = new TreeMap<>(attributes(schemaName, path, direction));
            attributes.put(Attr.VALUE, value);
            RenameMatcher.Rename rename = renames.get(value);
            String summary = owner + " value " + value + " removed";
            if (rename != null) {
                attributes.put(Attr.REPLACEMENT, rename.to());
                attributes.put(Attr.CONFIDENCE, Double.toString(rename.confidence()));
                summary = owner + " value " + value + " replaced by " + rename.to();
            }
            events.add(
                    ctx.event(
                            ChangeKind.ENUM_VALUE_REMOVED,
                            SymbolRef.enumValue(owner, value),
                            attributes,
                            summary));
        }
        for (String value : added) {
            Map<String, String> attributes = new TreeMap<>(attributes(schemaName, path, direction));
            attributes.put(Attr.VALUE, value);
            events.add(
                    ctx.event(
                            ChangeKind.ENUM_VALUE_ADDED,
                            SymbolRef.enumValue(owner, value),
                            attributes,
                            owner + " value " + value + " added"));
        }
    }

    private static Map<String, String> attributes(
            String schema, String field, String direction, String... extra) {
        Map<String, String> map = new TreeMap<>();
        map.put(Attr.SCHEMA, schema);
        map.put(Attr.FIELD, field);
        map.put(Attr.DIRECTION, direction);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            map.put(extra[i], extra[i + 1]);
        }
        return map;
    }
}
