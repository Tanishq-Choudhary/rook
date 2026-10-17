package dev.rook.eval.analysis;

import dev.rook.changes.jar.ApiSurface;
import dev.rook.changes.jar.ApiSurface.ClassApi;
import dev.rook.changes.jar.ApiSurface.FieldApi;
import dev.rook.changes.jar.ApiSurface.MethodApi;
import dev.rook.core.change.ChangeEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class SdkApiNotes {

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PROTECTED = 0x0004;
    private static final int MAX_LINES = 60;

    private SdkApiNotes() {}

    public static String render(List<ChangeEvent> events, ApiSurface surface) {
        String evidence = evidence(events);
        Set<String> chosen = new LinkedHashSet<>();
        for (ClassApi type : surface.classes().values()) {
            if (mentioned(evidence, type)) {
                chosen.add(type.name());
            }
        }
        Set<String> related = new TreeSet<>();
        for (String name : chosen) {
            ClassApi type = surface.classes().get(name);
            for (MethodApi method : type.methods().values()) {
                if (visible(method.access())) {
                    related.addAll(known(surface, method.parameterTypes()));
                    related.addAll(known(surface, List.of(method.returnType())));
                }
            }
            for (FieldApi field : type.fields().values()) {
                if (visible(field.access())) {
                    related.addAll(known(surface, List.of(field.type())));
                }
            }
        }
        chosen.addAll(related);
        List<String> lines = new ArrayList<>();
        for (String name : chosen) {
            describe(surface.classes().get(name), lines);
        }
        if (lines.isEmpty()) {
            return "";
        }
        List<String> kept = lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;
        return "The SDK after the change exposes these public types and members:\n"
                + String.join("\n", kept);
    }

    private static String evidence(List<ChangeEvent> events) {
        StringBuilder text = new StringBuilder();
        for (ChangeEvent event : events) {
            text.append(event.subject().name()).append(' ').append(event.summary()).append(' ');
            event.attributes().values().forEach(value -> text.append(value).append(' '));
        }
        return text.toString();
    }

    private static boolean mentioned(String evidence, ClassApi type) {
        return visible(type.access())
                && (evidence.contains(type.name()) || containsWord(evidence, type.simpleName()));
    }

    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (true) {
            int at = text.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
            int end = at + word.length();
            boolean endOk =
                    end == text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static Set<String> known(ApiSurface surface, List<String> types) {
        Set<String> found = new TreeSet<>();
        for (String type : types) {
            String bare = type == null ? "" : type.replace("[]", "");
            if (surface.classes().containsKey(bare)) {
                found.add(bare);
            }
        }
        return found;
    }

    private static boolean visible(int access) {
        return (access & (ACC_PUBLIC | ACC_PROTECTED)) != 0;
    }

    private static void describe(ClassApi type, List<String> lines) {
        lines.add("class " + type.name());
        for (MethodApi method : type.methods().values()) {
            if (!visible(method.access()) || method.name().equals("<clinit>")) {
                continue;
            }
            String parameters =
                    String.join(
                            ", ",
                            method.parameterTypes().stream().map(SdkApiNotes::shorten).toList());
            if (method.isConstructor()) {
                lines.add("  " + type.simpleName() + "(" + parameters + ")");
            } else {
                lines.add(
                        "  "
                                + shorten(method.returnType())
                                + " "
                                + method.name()
                                + "("
                                + parameters
                                + ")");
            }
        }
        for (FieldApi field : type.fields().values()) {
            if (visible(field.access())) {
                lines.add("  field " + shorten(field.type()) + " " + field.name());
            }
        }
    }

    private static String shorten(String type) {
        return type == null ? "void" : type.replace("java.lang.", "");
    }
}
