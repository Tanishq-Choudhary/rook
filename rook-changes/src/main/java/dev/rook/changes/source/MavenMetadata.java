package dev.rook.changes.source;

import dev.rook.changes.xml.SafeXml;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

public final class MavenMetadata {

    private MavenMetadata() {}

    public static List<String> versions(String xml) {
        Document document = SafeXml.parse(xml);
        NodeList nodes = document.getElementsByTagName("version");
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if ("versions".equals(nodes.item(i).getParentNode().getNodeName())) {
                String text = nodes.item(i).getTextContent().strip();
                if (!text.isEmpty()) {
                    versions.add(text);
                }
            }
        }
        return versions;
    }
}
