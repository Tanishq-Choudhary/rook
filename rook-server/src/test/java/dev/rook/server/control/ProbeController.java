package dev.rook.server.control;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProbeController {

    @GetMapping("/api/probe")
    String who(HttpServletRequest request) {
        return ApiKeyFilter.principal(request)
                .map(p -> p.name() + ":" + p.scopes().size())
                .orElse("none");
    }

    @PostMapping("/api/probe")
    String echo(@RequestBody byte[] body) throws IOException {
        return Integer.toString(new String(body, StandardCharsets.UTF_8).length());
    }
}
