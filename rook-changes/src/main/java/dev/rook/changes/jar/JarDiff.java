package dev.rook.changes.jar;

import dev.rook.changes.diff.DiffContext;
import dev.rook.changes.diff.RenameMatcher;
import dev.rook.changes.diff.Similarity;
import dev.rook.changes.jar.ApiSurface.ClassApi;
import dev.rook.changes.jar.ApiSurface.FieldApi;
import dev.rook.changes.jar.ApiSurface.MethodApi;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class JarDiff {

    private static final double CLASS_MATCH_THRESHOLD = 0.7;
    private static final int MAX_PARAMETER_DISTANCE = 2;

    private final DiffContext ctx;
    private final ApiSurface before;
    private final ApiSurface after;
    private final List<ChangeEvent> events = new ArrayList<>();

    JarDiff(DiffContext ctx, ApiSurface before, ApiSurface after) {
        this.ctx = ctx;
        this.before = before;
        this.after = after;
    }

    List<ChangeEvent> run() {
        Set<String> removed = new TreeSet<>(before.classes().keySet());
        removed.removeAll(after.classes().keySet());
        Set<String> added = new TreeSet<>(after.classes().keySet());
        added.removeAll(before.classes().keySet());

        Map<String, RenameMatcher.Rename> moves = matchClasses(removed, added);
        for (String name : removed) {
            RenameMatcher.Rename move = moves.get(name);
            if (move != null) {
                events.add(
                        ctx.event(
                                ChangeKind.CLASS_MOVED,
                                SymbolRef.javaClass(name),
                                Map.of(
                                        Attr.REPLACEMENT, move.to(),
                                        Attr.CONFIDENCE, Double.toString(move.confidence())),
                                name + " moved to " + move.to()));
                compareClass(before.classes().get(name), after.classes().get(move.to()));
            } else {
                events.add(
                        ctx.event(
                                ChangeKind.CLASS_REMOVED,
                                SymbolRef.javaClass(name),
                                Map.of(Attr.OWNER, name),
                                name + " removed"));
            }
        }
        for (Map.Entry<String, ClassApi> entry : before.classes().entrySet()) {
            ClassApi next = after.classes().get(entry.getKey());
            if (next != null) {
                compareClass(entry.getValue(), next);
            }
        }
        return events;
    }

    private Map<String, RenameMatcher.Rename> matchClasses(Set<String> removed, Set<String> added) {
        List<RenameMatcher.Rename> candidates = new ArrayList<>();
        for (String from : removed) {
            ClassApi old = before.classes().get(from);
            for (String to : added) {
                ClassApi next = after.classes().get(to);
                boolean sameSimpleName = old.simpleName().equals(next.simpleName());
                boolean samePackage = packageOf(from).equals(packageOf(to));
                boolean similarName =
                        samePackage && Similarity.names(old.simpleName(), next.simpleName()) >= 0.7;
                if (!sameSimpleName && !similarName) {
                    continue;
                }
                Set<String> left = signatures(old);
                Set<String> right = signatures(next);
                double score;
                if (left.isEmpty() && right.isEmpty()) {
                    score = sameSimpleName ? CLASS_MATCH_THRESHOLD : 0;
                } else {
                    score = jaccard(left, right);
                }
                if (score >= CLASS_MATCH_THRESHOLD) {
                    candidates.add(
                            new RenameMatcher.Rename(from, to, Math.round(score * 100) / 100.0));
                }
            }
        }
        candidates.sort(
                Comparator.comparingDouble(RenameMatcher.Rename::confidence)
                        .reversed()
                        .thenComparing(RenameMatcher.Rename::from)
                        .thenComparing(RenameMatcher.Rename::to));
        Map<String, RenameMatcher.Rename> accepted = new LinkedHashMap<>();
        Set<String> usedTo = new HashSet<>();
        for (RenameMatcher.Rename candidate : candidates) {
            if (accepted.containsKey(candidate.from()) || usedTo.contains(candidate.to())) {
                continue;
            }
            accepted.put(candidate.from(), candidate);
            usedTo.add(candidate.to());
        }
        return accepted;
    }

    private static Set<String> signatures(ClassApi api) {
        Set<String> signatures = new HashSet<>();
        for (MethodApi method : api.methods().values()) {
            if (!method.isConstructor()) {
                signatures.add(
                        method.name() + "(" + String.join(",", method.parameterTypes()) + ")");
            }
        }
        for (FieldApi field : api.fields().values()) {
            signatures.add("field:" + field.name());
        }
        return signatures;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        Set<String> common = new HashSet<>(left);
        common.retainAll(right);
        return union.isEmpty() ? 0 : (double) common.size() / union.size();
    }

    private static String packageOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    private void compareClass(ClassApi old, ClassApi now) {
        String owner = old.name();
        if (!old.deprecated() && now.deprecated()) {
            events.add(
                    ctx.event(
                            ChangeKind.CLASS_DEPRECATED,
                            SymbolRef.javaClass(owner),
                            Map.of(Attr.OWNER, owner),
                            owner + " deprecated"));
        }
        compareMethods(owner, old, now);
        compareFields(owner, old, now);
    }

    private void compareMethods(String owner, ClassApi old, ClassApi now) {
        Map<String, List<MethodApi>> removedByName = new TreeMap<>();
        Map<String, List<MethodApi>> addedByName = new TreeMap<>();
        for (MethodApi method : old.methods().values()) {
            if (!now.methods().containsKey(method.key()) && !inheritedAfter(now, method)) {
                removedByName.computeIfAbsent(method.name(), k -> new ArrayList<>()).add(method);
            }
        }
        for (MethodApi method : now.methods().values()) {
            if (!old.methods().containsKey(method.key())) {
                addedByName.computeIfAbsent(method.name(), k -> new ArrayList<>()).add(method);
            }
        }

        List<MethodApi> leftoverRemoved = new ArrayList<>();
        List<MethodApi> leftoverAdded = new ArrayList<>();
        Set<String> names = new TreeSet<>(removedByName.keySet());
        names.addAll(addedByName.keySet());
        for (String name : names) {
            List<MethodApi> removed = new ArrayList<>(removedByName.getOrDefault(name, List.of()));
            List<MethodApi> added = new ArrayList<>(addedByName.getOrDefault(name, List.of()));
            pairSignatureChanges(owner, removed, added);
            leftoverRemoved.addAll(removed);
            leftoverAdded.addAll(added);
        }

        pairRenames(owner, leftoverRemoved, leftoverAdded, old);

        for (MethodApi method : leftoverRemoved) {
            events.add(
                    ctx.event(
                            ChangeKind.METHOD_REMOVED,
                            SymbolRef.javaMethod(owner, method.name(), method.parameterTypes()),
                            Map.of(Attr.OWNER, owner, Attr.NAME, method.name()),
                            owner + "#" + method.name() + " removed"));
        }
        for (MethodApi method : leftoverAdded) {
            events.add(
                    ctx.event(
                            ChangeKind.METHOD_ADDED,
                            SymbolRef.javaMethod(owner, method.name(), method.parameterTypes()),
                            Map.of(Attr.OWNER, owner, Attr.NAME, method.name()),
                            owner + "#" + method.name() + " added"));
        }

        for (MethodApi method : old.methods().values()) {
            MethodApi next = now.methods().get(method.key());
            if (next != null && !method.deprecated() && next.deprecated()) {
                events.add(
                        ctx.event(
                                ChangeKind.METHOD_DEPRECATED,
                                SymbolRef.javaMethod(owner, method.name(), method.parameterTypes()),
                                Map.of(
                                        Attr.OWNER, owner,
                                        Attr.NAME, method.name(),
                                        Attr.FOR_REMOVAL, Boolean.toString(next.forRemoval())),
                                owner + "#" + method.name() + " deprecated"));
            }
        }
    }

    private void pairSignatureChanges(
            String owner, List<MethodApi> removed, List<MethodApi> added) {
        for (int i = 0; i < removed.size(); i++) {
            MethodApi old = removed.get(i);
            for (int j = 0; j < added.size(); j++) {
                MethodApi now = added.get(j);
                if (old.parameterTypes().equals(now.parameterTypes())) {
                    events.add(
                            ctx.event(
                                    ChangeKind.METHOD_RETURN_TYPE_CHANGED,
                                    SymbolRef.javaMethod(owner, old.name(), old.parameterTypes()),
                                    Map.of(
                                            Attr.OWNER, owner,
                                            Attr.NAME, old.name(),
                                            Attr.FROM, old.returnType(),
                                            Attr.TO, now.returnType()),
                                    owner + "#" + old.name() + " now returns " + now.returnType()));
                    removed.remove(i--);
                    added.remove(j);
                    break;
                }
            }
        }
        record Pair(MethodApi old, MethodApi now, int cost) {}
        List<Pair> pairs = new ArrayList<>();
        for (MethodApi old : removed) {
            for (MethodApi now : added) {
                int cost = listDistance(old.parameterTypes(), now.parameterTypes());
                if (cost <= MAX_PARAMETER_DISTANCE) {
                    pairs.add(new Pair(old, now, cost));
                }
            }
        }
        pairs.sort(
                Comparator.comparingInt(Pair::cost)
                        .thenComparing(p -> p.old().key())
                        .thenComparing(p -> p.now().key()));
        Set<String> usedOld = new HashSet<>();
        Set<String> usedNew = new HashSet<>();
        for (Pair pair : pairs) {
            if (usedOld.contains(pair.old().key()) || usedNew.contains(pair.now().key())) {
                continue;
            }
            usedOld.add(pair.old().key());
            usedNew.add(pair.now().key());
            Map<String, String> attributes = new TreeMap<>();
            attributes.put(Attr.OWNER, owner);
            attributes.put(Attr.NAME, pair.old().name());
            attributes.put(Attr.OLD_PARAMETERS, String.join(",", pair.old().parameterTypes()));
            attributes.put(Attr.NEW_PARAMETERS, String.join(",", pair.now().parameterTypes()));
            if (!pair.old().returnType().equals(pair.now().returnType())) {
                attributes.put(Attr.FROM, pair.old().returnType());
                attributes.put(Attr.TO, pair.now().returnType());
            }
            events.add(
                    ctx.event(
                            ChangeKind.METHOD_PARAMETERS_CHANGED,
                            SymbolRef.javaMethod(
                                    owner, pair.old().name(), pair.old().parameterTypes()),
                            attributes,
                            owner + "#" + pair.old().name() + " parameters changed"));
        }
        removed.removeIf(m -> usedOld.contains(m.key()));
        added.removeIf(m -> usedNew.contains(m.key()));
    }

    private void pairRenames(
            String owner, List<MethodApi> removed, List<MethodApi> added, ClassApi old) {
        Set<String> oldNames = new HashSet<>();
        old.methods().values().forEach(m -> oldNames.add(m.name()));
        List<String> removedKeys = new ArrayList<>();
        List<String> addedKeys = new ArrayList<>();
        Map<String, String> removedTypes = new HashMap<>();
        Map<String, String> addedTypes = new HashMap<>();
        Map<String, MethodApi> byKey = new HashMap<>();
        for (MethodApi method : removed) {
            if (!method.isConstructor()) {
                removedKeys.add(method.key());
                removedTypes.put(
                        method.key(),
                        method.descriptor().substring(method.descriptor().indexOf('(')));
                byKey.put(method.key(), method);
            }
        }
        for (MethodApi method : added) {
            if (!method.isConstructor() && !oldNames.contains(method.name())) {
                addedKeys.add(method.key());
                addedTypes.put(
                        method.key(),
                        method.descriptor().substring(method.descriptor().indexOf('(')));
                byKey.put(method.key(), method);
            }
        }
        for (RenameMatcher.Rename rename :
                RenameMatcher.match(
                        removedKeys,
                        addedKeys,
                        removedTypes,
                        addedTypes,
                        (a, b) -> Similarity.names(byKey.get(a).name(), byKey.get(b).name()))) {
            MethodApi from = byKey.get(rename.from());
            MethodApi to = byKey.get(rename.to());
            events.add(
                    ctx.event(
                            ChangeKind.METHOD_RENAMED,
                            SymbolRef.javaMethod(owner, from.name(), from.parameterTypes()),
                            Map.of(
                                    Attr.OWNER, owner,
                                    Attr.NAME, from.name(),
                                    Attr.REPLACEMENT, to.name(),
                                    Attr.CONFIDENCE, Double.toString(rename.confidence())),
                            owner + "#" + from.name() + " renamed to " + to.name()));
            removed.remove(from);
            added.remove(to);
        }
    }

    private boolean inheritedAfter(ClassApi owner, MethodApi method) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        enqueueParents(owner, queue);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!seen.add(name)) {
                continue;
            }
            ClassApi parent = after.classes().get(name);
            if (parent == null) {
                continue;
            }
            if (parent.methods().containsKey(method.key())) {
                return true;
            }
            enqueueParents(parent, queue);
        }
        return false;
    }

    private static void enqueueParents(ClassApi api, Deque<String> queue) {
        if (api.superName() != null) {
            queue.add(api.superName());
        }
        queue.addAll(api.interfaces());
    }

    private void compareFields(String owner, ClassApi old, ClassApi now) {
        for (FieldApi field : old.fields().values()) {
            FieldApi next = now.fields().get(field.name());
            if (next == null) {
                events.add(
                        ctx.event(
                                ChangeKind.JAVA_FIELD_REMOVED,
                                SymbolRef.javaField(owner, field.name()),
                                Map.of(
                                        Attr.OWNER, owner,
                                        Attr.NAME, field.name(),
                                        Attr.ENUM_CONSTANT,
                                                Boolean.toString(field.isEnumConstant())),
                                owner + "#" + field.name() + " removed"));
                continue;
            }
            if (!field.descriptor().equals(next.descriptor())) {
                events.add(
                        ctx.event(
                                ChangeKind.JAVA_FIELD_TYPE_CHANGED,
                                SymbolRef.javaField(owner, field.name()),
                                Map.of(
                                        Attr.OWNER, owner,
                                        Attr.NAME, field.name(),
                                        Attr.FROM, field.type(),
                                        Attr.TO, next.type()),
                                owner + "#" + field.name() + " is now " + next.type()));
            }
            if (!field.deprecated() && next.deprecated()) {
                events.add(
                        ctx.event(
                                ChangeKind.FIELD_DEPRECATED,
                                SymbolRef.javaField(owner, field.name()),
                                Map.of(Attr.OWNER, owner, Attr.NAME, field.name()),
                                owner + "#" + field.name() + " deprecated"));
            }
        }
    }

    private static int listDistance(List<String> a, List<String> b) {
        int[][] d = new int[a.size() + 1][b.size() + 1];
        for (int i = 0; i <= a.size(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.size(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                int cost = a.get(i - 1).equals(b.get(j - 1)) ? 0 : 1;
                d[i][j] =
                        Math.min(
                                Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        return d[a.size()][b.size()];
    }
}
