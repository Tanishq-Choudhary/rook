package dev.rook.agent.loop;

import dev.rook.agent.safety.UntrustedContent;
import dev.rook.codeintel.impact.Impact;
import dev.rook.core.change.ChangeEvent;
import java.util.List;

final class AgentPrompts {

    static final String SYSTEM =
            "You migrate a Java repository after a third party API or SDK changed. You work only through"
                    + " the tools you are given.\n\n"
                    + "Rules:\n"
                    + "1. Start with list_impacted and read_migration_notes, then read the code around"
                    + " each place before changing it.\n"
                    + "2. Make the smallest change that restores correct behaviour. Do not reformat,"
                    + " rename or clean up anything unrelated.\n"
                    + "3. Use apply_edit with search text copied exactly from read_file output, without"
                    + " line numbers. If an edit is rejected, read the file again.\n"
                    + "4. Never touch build files, CI workflows or Rook configuration, and never add"
                    + " dependencies. If the migration needs that, say so in unresolved.\n"
                    + "5. Run run_tests after your edits. If tests fail, fix the cause shown in the"
                    + " output instead of weakening a test.\n"
                    + "6. When you are done, or stuck, call finish with an honest summary. Put every"
                    + " place you could not fix in unresolved. Do not claim success you did not verify.\n"
                    + "7. Edit as soon as you know what the new API looks like. The task text may list"
                    + " the new API. Once run_tests passes after your edits, call finish at once and do"
                    + " not read files or run the tests again.\n\n"
                    + UntrustedContent.NOTICE;

    private AgentPrompts() {}

    static String task(
            String repository, List<ChangeEvent> events, List<Impact> impacts, String preface) {
        StringBuilder text = new StringBuilder();
        text.append("Repository: ").append(repository).append("\n\n");
        if (!preface.isEmpty()) {
            text.append(preface).append("\n\n");
        }
        text.append("Upstream changes:\n");
        for (ChangeEvent event : events) {
            text.append("- ")
                    .append(event.severity())
                    .append(' ')
                    .append(event.kind())
                    .append(": ")
                    .append(event.summary())
                    .append('\n');
        }
        int locations = impacts.stream().mapToInt(i -> i.locations().size()).sum();
        text.append("\nThe analysis found ")
                .append(locations)
                .append(" affected places across ")
                .append(impacts.size())
                .append(" changes. Fix them, run the tests, then call finish.");
        return text.toString();
    }

    static String testFailure(int round, int limit, String description) {
        return "finish was not accepted because verification failed (repair round "
                + round
                + " of "
                + limit
                + "):\n"
                + description
                + "\nFix the cause, run run_tests, then call finish again.";
    }
}
