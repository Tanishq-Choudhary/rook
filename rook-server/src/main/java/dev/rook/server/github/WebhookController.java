package dev.rook.server.github;

import dev.rook.core.error.RookException;
import dev.rook.github.webhook.SignatureVerifier;
import dev.rook.github.webhook.WebhookProcessor;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnExpression(
        "!'${rook.github.app-id:}'.isEmpty() and ${rook.workflow.enabled:true}"
                + " and !'${rook.github.webhook-secret:}'.isEmpty()")
class WebhookController {

    private final SignatureVerifier signatures;
    private final WebhookProcessor processor;

    WebhookController(SignatureVerifier signatures, WebhookProcessor processor) {
        this.signatures = signatures;
        this.processor = processor;
    }

    @PostMapping("/webhooks/github")
    ResponseEntity<Map<String, String>> receive(
            @RequestBody byte[] body,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(name = "X-GitHub-Delivery", required = false) String delivery,
            @RequestHeader(name = "X-GitHub-Event", required = false) String event) {
        if (!signatures.verify(body, signature)) {
            throw RookException.unauthorized("the webhook signature is not valid");
        }
        WebhookProcessor.Outcome outcome = processor.process(delivery, event, body);
        return ResponseEntity.ok(Map.of("outcome", outcome.name()));
    }
}
