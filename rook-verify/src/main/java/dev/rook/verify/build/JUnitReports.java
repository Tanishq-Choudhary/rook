package dev.rook.verify.build;

import dev.rook.core.error.RookException;
import dev.rook.core.xml.SafeXml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class JUnitReports {

    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_FILES = 2000;
    private static final int MAX_MESSAGE = 500;

    private JUnitReports() {}

    public static TestReport readAll(Path root) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 8)) {
            walk.filter(Files::isRegularFile)
                    .filter(JUnitReports::isReport)
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_FILES)
                    .forEach(files::add);
        } catch (IOException e) {
            return TestReport.empty();
        }
        List<TestCaseResult> results = new ArrayList<>();
        for (Path file : files) {
            try {
                if (Files.size(file) <= MAX_FILE_BYTES) {
                    results.addAll(parse(Files.readString(file, StandardCharsets.UTF_8)));
                }
            } catch (IOException | RookException e) {
                continue;
            }
        }
        return TestReport.of(results);
    }

    private static boolean isReport(Path file) {
        String name = file.getFileName().toString();
        String path = file.toString().replace('\\', '/');
        return name.startsWith("TEST-")
                && name.endsWith(".xml")
                && (path.contains("/surefire-reports/")
                        || path.contains("/failsafe-reports/")
                        || path.contains("/test-results/"));
    }

    public static List<TestCaseResult> parse(String xml) {
        Element root = SafeXml.parse(xml).getDocumentElement();
        List<TestCaseResult> results = new ArrayList<>();
        NodeList cases = root.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element element = (Element) cases.item(i);
            String className = element.getAttribute("classname");
            String name = element.getAttribute("name");
            if (name.isBlank()) {
                continue;
            }
            String id = className.isBlank() ? name : className + "#" + name;
            results.add(
                    new TestCaseResult(id, outcome(element), message(element), seconds(element)));
        }
        return results;
    }

    private static TestCaseResult.Outcome outcome(Element element) {
        if (has(element, "error")) {
            return TestCaseResult.Outcome.ERROR;
        }
        if (has(element, "failure")) {
            return TestCaseResult.Outcome.FAILED;
        }
        if (has(element, "skipped")) {
            return TestCaseResult.Outcome.SKIPPED;
        }
        return TestCaseResult.Outcome.PASSED;
    }

    private static boolean has(Element parent, String tag) {
        return !directChildren(parent, tag).isEmpty();
    }

    private static List<Element> directChildren(Element parent, String tag) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child && child.getTagName().equals(tag)) {
                found.add(child);
            }
        }
        return found;
    }

    private static String message(Element element) {
        for (String tag : List.of("error", "failure", "skipped")) {
            List<Element> children = directChildren(element, tag);
            if (!children.isEmpty()) {
                Element child = children.get(0);
                String text = child.getAttribute("message");
                if (text.isBlank()) {
                    text = child.getTextContent().strip().lines().findFirst().orElse("");
                }
                String flat = text.replaceAll("\\s+", " ").strip();
                return flat.length() <= MAX_MESSAGE ? flat : flat.substring(0, MAX_MESSAGE);
            }
        }
        return "";
    }

    private static double seconds(Element element) {
        try {
            return Double.parseDouble(element.getAttribute("time"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
