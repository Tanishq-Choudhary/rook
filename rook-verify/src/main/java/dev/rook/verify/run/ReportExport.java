package dev.rook.verify.run;

import dev.rook.verify.build.BuildPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ReportExport {

    static final String BEGIN = "@@ROOK-REPORTS-BEGIN@@";
    static final String END = "@@ROOK-REPORTS-END@@";
    static final int MAX_ENCODED_BYTES = 4_000_000;

    private static final Pattern BLOCK =
            Pattern.compile(
                    Pattern.quote(BEGIN) + "\\s*([A-Za-z0-9+/=]*)\\s*" + Pattern.quote(END));

    private ReportExport() {}

    static String script(List<String> command) {
        String quoted = command.stream().map(ReportExport::quote).collect(Collectors.joining(" "));
        return "cp -R "
                + BuildPlan.SOURCE_PATH
                + "/. "
                + BuildPlan.WORK_PATH
                + "/ || exit 97; cd "
                + BuildPlan.WORK_PATH
                + " || exit 98; "
                + quoted
                + "; code=$?; echo; echo "
                + BEGIN
                + "; (find . -type f \\( -path '*/surefire-reports/TEST-*.xml'"
                + " -o -path '*/failsafe-reports/TEST-*.xml' \\) -size -2000k 2>/dev/null"
                + " | head -n 500 | tar -czf - -T - 2>/dev/null | base64 -w0"
                + " | head -c "
                + MAX_ENCODED_BYTES
                + "); echo; echo "
                + END
                + "; exit $code";
    }

    static String quote(String token) {
        return "'" + token.replace("'", "'\\''") + "'";
    }

    static int extract(String stdout, Path target) throws IOException {
        Matcher matcher = BLOCK.matcher(stdout);
        String encoded = null;
        while (matcher.find()) {
            encoded = matcher.group(1);
        }
        if (encoded == null || encoded.isEmpty()) {
            return 0;
        }
        byte[] archive;
        try {
            archive = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IOException("the exported reports are not valid base64", e);
        }
        Files.createDirectories(target);
        return new TarExtractor(1000, 20L * 1024 * 1024).extract(archive, target);
    }
}
