package dev.rook.server.control;

import dev.rook.core.json.Json;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class Problems {

    private Problems() {}

    static void write(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:rook:error:" + code);
        body.put("title", code.toUpperCase());
        body.put("status", status);
        body.put("detail", detail);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(Json.write(body));
    }
}
