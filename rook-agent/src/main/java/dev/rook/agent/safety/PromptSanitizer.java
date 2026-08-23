package dev.rook.agent.safety;

public final class PromptSanitizer {

    private static final String TRUNCATED = "\n[truncated, %d more characters]";

    private final SecretRedactor redactor;
    private final UntrustedContent untrusted;
    private final int maxChars;

    public PromptSanitizer(SecretRedactor redactor, UntrustedContent untrusted, int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.redactor = redactor;
        this.untrusted = untrusted;
        this.maxChars = maxChars;
    }

    public static PromptSanitizer standard(int maxChars) {
        return new PromptSanitizer(new SecretRedactor(), UntrustedContent.secure(), maxChars);
    }

    public String prepare(String label, String content) {
        String redacted = redactor.redact(content);
        String bounded = redacted;
        if (redacted.length() > maxChars) {
            bounded =
                    redacted.substring(0, maxChars)
                            + String.format(TRUNCATED, redacted.length() - maxChars);
        }
        return untrusted.wrap(label, bounded);
    }
}
