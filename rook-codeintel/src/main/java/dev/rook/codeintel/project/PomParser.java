package dev.rook.codeintel.project;

import dev.rook.core.xml.SafeXml;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class PomParser {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private PomParser() {}

    record Pom(
            String groupId,
            String artifactId,
            String version,
            Map<String, String> properties,
            Map<String, String> managedVersions,
            List<Dependency> dependencies) {}

    static Pom parse(String xml, String path) {
        Document document = SafeXml.parse(xml);
        Element project = document.getDocumentElement();
        Element parent = child(project, "parent");
        String groupId =
                firstNonBlank(
                        text(project, "groupId"), parent == null ? "" : text(parent, "groupId"));
        String version =
                firstNonBlank(
                        text(project, "version"), parent == null ? "" : text(parent, "version"));
        Map<String, String> properties = new HashMap<>();
        Element props = child(project, "properties");
        if (props != null) {
            for (Node node = props.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element) {
                    properties.put(element.getTagName(), element.getTextContent().strip());
                }
            }
        }
        properties.put("project.version", version);
        properties.put("project.groupId", groupId);
        properties.put("pom.version", version);
        if (parent != null) {
            properties.put("project.parent.version", text(parent, "version"));
        }

        Map<String, String> managed = new HashMap<>();
        Element management = child(project, "dependencyManagement");
        if (management != null) {
            Element managedDeps = child(management, "dependencies");
            for (Element dependency : children(managedDeps, "dependency")) {
                String version2 = resolve(text(dependency, "version"), properties);
                if (!version2.isEmpty()) {
                    managed.put(
                            text(dependency, "groupId") + ":" + text(dependency, "artifactId"),
                            version2);
                }
            }
        }

        List<Dependency> dependencies = new ArrayList<>();
        for (Element dependency : children(child(project, "dependencies"), "dependency")) {
            String group = resolve(text(dependency, "groupId"), properties);
            String artifact = resolve(text(dependency, "artifactId"), properties);
            if (group.isEmpty() || artifact.isEmpty()) {
                continue;
            }
            String declared = resolve(text(dependency, "version"), properties);
            String resolved =
                    declared.isEmpty()
                            ? managed.getOrDefault(group + ":" + artifact, "")
                            : declared;
            String scope = text(dependency, "scope");
            dependencies.add(
                    new Dependency(
                            group,
                            artifact,
                            resolved.isEmpty() ? null : resolved,
                            scope.isEmpty() ? "compile" : scope,
                            path));
        }
        return new Pom(
                groupId, text(project, "artifactId"), version, properties, managed, dependencies);
    }

    private static String resolve(String value, Map<String, String> properties) {
        String current = value;
        for (int pass = 0; pass < 5 && current.contains("${"); pass++) {
            Matcher matcher = PLACEHOLDER.matcher(current);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                matcher.appendReplacement(
                        out,
                        Matcher.quoteReplacement(
                                replacement == null ? matcher.group() : replacement));
            }
            matcher.appendTail(out);
            current = out.toString();
        }
        return current.contains("${") ? "" : current;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName(element).equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        if (parent == null) {
            return found;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && localName(element).equals(name)) {
                found.add(element);
            }
        }
        return found;
    }

    private static String text(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? "" : child.getTextContent().strip();
    }

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
    }

    private static String firstNonBlank(String first, String second) {
        return first.isBlank() ? second : first;
    }
}
