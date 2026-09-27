package dev.rook.github.publish;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class PrBody {

    static final int MAX_LENGTH = 60_000;
    private static final int MAX_LIST = 25;

    private PrBody() {}

    public static String title(PrReport report) {
        String title =
                "Update for "
                        + report.sourceName()
                        + " "
                        + report.fromVersion()
                        + " to "
                        + report.toVersion();
        return title.length() > 120 ? title.substring(0, 117) + "..." : title;
    }

    public static String render(PrReport report) {
        StringBuilder body = new StringBuilder();
        body.append(report.sourceName())
                .append(" changed from ")
                .append(report.fromVersion())
                .append(" to ")
                .append(report.toVersion())
                .append(" and this code uses parts of it that moved.\n\n");
        section(body, "What changed upstream", report.changes());
        body.append("## Where it touches this repository\n\n");
        if (report.callSites().isEmpty()) {
            body.append("No call sites were found.\n\n");
        } else {
            Map<String, Integer> perFile = new TreeMap<>();
            report.callSites().forEach(site -> perFile.merge(site.file(), 1, Integer::sum));
            body.append(report.callSites().size())
                    .append(report.callSites().size() == 1 ? " place in " : " places in ")
                    .append(perFile.size())
                    .append(perFile.size() == 1 ? " file.\n\n" : " files.\n\n");
            int shown = 0;
            for (PrReport.CallSite site : report.callSites()) {
                if (shown++ == MAX_LIST) {
                    body.append("- and ")
                            .append(report.callSites().size() - MAX_LIST)
                            .append(" more\n");
                    break;
                }
                body.append("- `").append(site.file()).append(':').append(site.line()).append('`');
                if (site.note() != null && !site.note().isBlank()) {
                    body.append(" ").append(clean(site.note()));
                }
                body.append('\n');
            }
            body.append('\n');
        }
        body.append("## What this pull request does\n\n");
        body.append(report.diff().files())
                .append(report.diff().files() == 1 ? " file" : " files")
                .append(" changed, ")
                .append(report.diff().added())
                .append(" lines added, ")
                .append(report.diff().removed())
                .append(" removed. Produced by the ")
                .append(report.strategy())
                .append(" strategy.\n\n");
        verification(body, report.verification());
        section(
                body,
                "Risks and open items",
                report.risks().isEmpty() ? List.of("None recorded.") : report.risks());
        if (report.jobUrl() != null && !report.jobUrl().isBlank()) {
            body.append("Job details: ").append(report.jobUrl()).append('\n');
        }
        String text = body.toString().stripTrailing() + "\n";
        return text.length() > MAX_LENGTH
                ? text.substring(0, MAX_LENGTH - 40) + "\n\nThe description was cut here.\n"
                : text;
    }

    private static void verification(StringBuilder body, PrReport.Verification v) {
        body.append("## Verification\n\n");
        body.append(
                String.format(
                        Locale.ROOT,
                        "Before the change: %d tests ran, %d failed.%nAfter the change: %d tests ran, %d failed.%n%n",
                        v.baselineRun(),
                        v.baselineFailed(),
                        v.patchedRun(),
                        v.patchedFailed()));
        if (!v.flaky().isEmpty()) {
            body.append(
                    "Tests that behaved inconsistently when re-run and were not counted against the change:\n\n");
            v.flaky().stream()
                    .limit(MAX_LIST)
                    .forEach(name -> body.append("- `").append(name).append("`\n"));
            body.append('\n');
        }
        if (!v.gates().isEmpty()) {
            body.append("Checks passed: ").append(String.join(", ", v.gates())).append(".\n\n");
        }
    }

    private static void section(StringBuilder body, String heading, List<String> lines) {
        body.append("## ").append(heading).append("\n\n");
        int shown = 0;
        for (String line : lines) {
            if (shown++ == MAX_LIST) {
                body.append("- and ").append(lines.size() - MAX_LIST).append(" more\n");
                break;
            }
            body.append("- ").append(clean(line)).append('\n');
        }
        body.append('\n');
    }

    private static String clean(String text) {
        String single = text.replace('\r', ' ').replace('\n', ' ').strip();
        return single.length() > 300 ? single.substring(0, 297) + "..." : single;
    }
}
