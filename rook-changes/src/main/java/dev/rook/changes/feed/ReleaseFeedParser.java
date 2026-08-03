package dev.rook.changes.feed;

import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;

public final class ReleaseFeedParser {

    private static final Pattern CHANGELOG_HEADING =
            Pattern.compile("^##\\s+\\[?v?(\\d[\\w.+-]*)\\]?(?:\\s*[-(]\\s*([\\d-]{8,10}))?.*$");

    private ReleaseFeedParser() {}

    public static List<ReleaseEntry> parseGitHubReleases(String json) {
        JsonNode root = Json.tree(json);
        if (!root.isArray()) {
            throw RookException.invalid("release feed must be a json array");
        }
        List<ReleaseEntry> entries = new ArrayList<>();
        for (JsonNode node : root) {
            if (node.path("draft").asBoolean(false)) {
                continue;
            }
            String tag = node.path("tag_name").asString("");
            if (tag.isBlank()) {
                continue;
            }
            entries.add(
                    new ReleaseEntry(
                            ReleaseEntry.normalizeVersion(tag),
                            node.path("name").asString(tag),
                            instant(node.path("published_at").asString("")),
                            node.path("body").asString(""),
                            node.path("html_url").asString(""),
                            node.path("prerelease").asBoolean(false)));
        }
        return entries;
    }

    public static List<ReleaseEntry> parseAtomOrRss(String xml) {
        Document document = parseXml(xml);
        List<ReleaseEntry> entries = new ArrayList<>();
        NodeList atom = document.getElementsByTagNameNS("*", "entry");
        for (int i = 0; i < atom.getLength(); i++) {
            Element entry = (Element) atom.item(i);
            String title = text(entry, "title");
            String link = atomLink(entry);
            String body = firstNonBlank(text(entry, "content"), text(entry, "summary"));
            entries.add(
                    new ReleaseEntry(
                            ReleaseEntry.normalizeVersion(versionFromTitle(title, link)),
                            title,
                            instant(
                                    firstNonBlank(
                                            text(entry, "updated"), text(entry, "published"))),
                            body,
                            link,
                            false));
        }
        NodeList rss = document.getElementsByTagName("item");
        for (int i = 0; i < rss.getLength(); i++) {
            Element item = (Element) rss.item(i);
            String title = text(item, "title");
            String link = text(item, "link");
            entries.add(
                    new ReleaseEntry(
                            ReleaseEntry.normalizeVersion(versionFromTitle(title, link)),
                            title,
                            rfc1123(text(item, "pubDate")),
                            text(item, "description"),
                            link,
                            false));
        }
        return entries;
    }

    public static List<ReleaseEntry> parseMarkdownChangelog(String markdown) {
        List<ReleaseEntry> entries = new ArrayList<>();
        String version = null;
        String date = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            Matcher heading = CHANGELOG_HEADING.matcher(line);
            if (heading.matches()) {
                if (version != null) {
                    entries.add(entry(version, date, body));
                }
                version = heading.group(1);
                date = heading.group(2);
                body.setLength(0);
            } else if (version != null) {
                body.append(line).append('\n');
            }
        }
        if (version != null) {
            entries.add(entry(version, date, body));
        }
        return entries;
    }

    private static ReleaseEntry entry(String version, String date, StringBuilder body) {
        Instant published = null;
        if (date != null) {
            try {
                published = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                published = null;
            }
        }
        return new ReleaseEntry(version, version, published, body.toString().strip(), "", false);
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw RookException.invalid("feed is not valid xml", e);
        }
    }

    private static String text(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagNameNS("*", name);
        if (nodes.getLength() == 0) {
            nodes = parent.getElementsByTagName(name);
        }
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getParentNode() == parent) {
                return node.getTextContent().strip();
            }
        }
        return "";
    }

    private static String atomLink(Element entry) {
        NodeList links = entry.getElementsByTagNameNS("*", "link");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            String rel = link.getAttribute("rel");
            if (rel.isEmpty() || "alternate".equals(rel)) {
                return link.getAttribute("href");
            }
        }
        return "";
    }

    private static String versionFromTitle(String title, String link) {
        Matcher matcher = Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?[\\w.+-]*)").matcher(title);
        if (matcher.find()) {
            return matcher.group(1);
        }
        int slash = link.lastIndexOf('/');
        return slash >= 0 && slash + 1 < link.length() ? link.substring(slash + 1) : title;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static Instant rfc1123(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
