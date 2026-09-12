package dev.rook.eval.generate;

import java.util.Map;

final class Template {

    private Template() {}

    static String render(String text, Map<String, String> values) {
        String result = text;
        for (Map.Entry<String, String> value : values.entrySet()) {
            result = result.replace("${" + value.getKey() + "}", value.getValue());
        }
        if (result.contains("${")) {
            throw new IllegalStateException("unresolved placeholder in template");
        }
        return result;
    }
}
