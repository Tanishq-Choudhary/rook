package dev.rook.core.security;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SensitivePaths {

    private static final List<Pattern> PATTERNS =
            List.of(
                    Pattern.compile("(^|/)\\.env(\\..*)?$"),
                    Pattern.compile("(^|/)\\.npmrc$"),
                    Pattern.compile("(^|/)\\.pypirc$"),
                    Pattern.compile("(^|/)\\.netrc$"),
                    Pattern.compile("(^|/)\\.git-credentials$"),
                    Pattern.compile("(^|/)\\.aws/.*"),
                    Pattern.compile("(^|/)\\.ssh/.*"),
                    Pattern.compile("(^|/)id_(rsa|dsa|ecdsa|ed25519)(\\.pub)?$"),
                    Pattern.compile(".*\\.(pem|key|p12|pfx|jks|keystore|kdbx|ppk)$"),
                    Pattern.compile("(^|/)credentials(\\..*)?$"),
                    Pattern.compile("(^|/)secrets?(\\..*)?$"),
                    Pattern.compile("(^|/)service-account.*\\.json$"),
                    Pattern.compile("(^|/)application-(prod|production)\\.(yml|yaml|properties)$"),
                    Pattern.compile(".*\\.tfstate(\\..*)?$"),
                    Pattern.compile(".*\\.tfvars$"));

    private SensitivePaths() {}

    public static boolean isSensitive(String relativePath) {
        String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }
}
