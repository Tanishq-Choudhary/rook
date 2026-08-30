package dev.rook.agent.loop;

import java.util.List;

public record TestRun(
        boolean compiled,
        int executed,
        int failed,
        List<String> failures,
        String outputTail,
        boolean timedOut) {

    public TestRun {
        failures = List.copyOf(failures);
        outputTail = outputTail == null ? "" : outputTail;
    }

    public static TestRun passing(int executed) {
        return new TestRun(true, executed, 0, List.of(), "", false);
    }

    public static TestRun failing(List<String> failures) {
        return new TestRun(true, failures.size() + 1, failures.size(), failures, "", false);
    }

    public static TestRun notCompiling(String output) {
        return new TestRun(false, 0, 0, List.of(), output, false);
    }

    public boolean passed() {
        return compiled && executed > 0 && failed == 0 && !timedOut;
    }

    public String describe() {
        if (timedOut) {
            return "the test run timed out";
        }
        if (!compiled) {
            return "the code does not compile:\n" + outputTail;
        }
        if (executed == 0) {
            return "no tests were executed, so the change cannot be verified";
        }
        if (failed == 0) {
            return executed + " tests ran and all passed";
        }
        StringBuilder text = new StringBuilder();
        text.append(failed).append(" of ").append(executed).append(" tests failed:\n");
        failures.stream()
                .limit(20)
                .forEach(failure -> text.append("- ").append(failure).append('\n'));
        if (failures.size() > 20) {
            text.append("- ... and ").append(failures.size() - 20).append(" more\n");
        }
        return text.toString();
    }
}
