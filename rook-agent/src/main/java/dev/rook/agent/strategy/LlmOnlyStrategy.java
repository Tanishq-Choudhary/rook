package dev.rook.agent.strategy;

import dev.rook.agent.loop.AgentBudget;
import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.loop.TraceStep;
import dev.rook.agent.model.ModelClient;
import dev.rook.agent.model.ModelException;
import dev.rook.agent.model.ModelMessage;
import dev.rook.agent.model.ModelRequest;
import dev.rook.agent.model.ModelResponse;
import dev.rook.agent.model.PriceTable;
import dev.rook.agent.model.Usage;
import dev.rook.agent.patch.PatchRejected;
import dev.rook.agent.safety.PromptSanitizer;
import dev.rook.agent.safety.UntrustedContent;
import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspaceViolation;
import dev.rook.core.change.ChangeEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LlmOnlyStrategy implements Strategy {

    public static final String NAME = "llm-only";

    private static final int MAX_FILES = 10;
    private static final int MAX_OUTPUT_TOKENS = 8_192;
    private static final Pattern FILE_BLOCK =
            Pattern.compile("<file path=\"([^\"]+)\">\\R?(.*?)</file>", Pattern.DOTALL);

    private static final String SYSTEM =
            "You update one Java source file after a third party API or SDK changed. Reply with the"
                    + " complete updated file inside <file path=\"...\"> and </file> tags, using the path"
                    + " you were given. Change only what the upstream change requires and keep everything"
                    + " else byte for byte. If the file needs no change, reply with the word UNCHANGED.\n\n"
                    + UntrustedContent.NOTICE;

    private final ModelClient model;
    private final PriceTable prices;
    private final AgentBudget budget;
    private final PromptSanitizer sanitizer;

    public LlmOnlyStrategy(
            ModelClient model, PriceTable prices, AgentBudget budget, PromptSanitizer sanitizer) {
        this.model = model;
        this.prices = prices;
        this.budget = budget;
        this.sanitizer = sanitizer;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public StrategyOutcome run(AgentTask task, Workspace workspace) {
        long started = System.nanoTime();
        Usage usage = Usage.NONE;
        long cost = 0;
        List<String> unresolved = new ArrayList<>();
        List<TraceStep> trace = new ArrayList<>();
        List<String> files = task.report().affectedFiles();
        if (files.size() > MAX_FILES) {
            unresolved.add((files.size() - MAX_FILES) + " affected files were not attempted");
            files = files.subList(0, MAX_FILES);
        }
        String status = "COMPLETE";
        for (String file : files) {
            if (usage.totalTokens() >= budget.maxTokens() || cost > budget.maxCostMicros()) {
                unresolved.add(file + ": budget exhausted");
                status = "BUDGET";
                continue;
            }
            long began = System.nanoTime();
            ModelResponse response;
            try {
                response =
                        model.complete(
                                ModelRequest.of(
                                        SYSTEM,
                                        List.of(ModelMessage.user(prompt(task, workspace, file))),
                                        MAX_OUTPUT_TOKENS));
            } catch (ModelException | WorkspaceViolation e) {
                unresolved.add(file + ": " + e.getMessage());
                trace.add(step(trace, "model", file, e.getMessage(), began, true));
                status = "FAILED";
                if (e instanceof ModelException) {
                    break;
                }
                continue;
            }
            usage = usage.plus(response.usage());
            cost +=
                    prices.knows(response.model())
                            ? prices.costMicros(response.model(), response.usage())
                            : 0;
            String problem = apply(workspace, file, response.text());
            trace.add(
                    step(
                            trace,
                            "model",
                            file,
                            problem == null ? "applied" : problem,
                            began,
                            problem != null));
            if (problem != null) {
                unresolved.add(file + ": " + problem);
                status = "PARTIAL";
            }
        }
        return new StrategyOutcome(
                NAME,
                workspace.diff(),
                workspace.changedPaths(),
                unresolved,
                usage,
                cost,
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                false,
                status,
                trace);
    }

    private String prompt(AgentTask task, Workspace workspace, String file) {
        StringBuilder text = new StringBuilder();
        if (!task.preface().isEmpty()) {
            text.append(task.preface()).append("\n\n");
        }
        text.append("Upstream changes:\n");
        for (ChangeEvent event : task.events()) {
            text.append("- ").append(event.summary()).append('\n');
        }
        text.append("\nFile path: ").append(file).append("\n\n");
        text.append(sanitizer.prepare(file, workspace.read(file)));
        return text.toString();
    }

    private static String apply(Workspace workspace, String file, String reply) {
        if (reply.strip().equals("UNCHANGED")) {
            return null;
        }
        Matcher matcher = FILE_BLOCK.matcher(reply);
        while (matcher.find()) {
            if (!matcher.group(1).equals(file)) {
                continue;
            }
            String content = matcher.group(2);
            try {
                String original = workspace.read(file);
                if (original.endsWith("\n") && !content.endsWith("\n")) {
                    content = content + "\n";
                }
                if (content.equals(original)) {
                    return null;
                }
                workspace.rewrite(file, content);
                return null;
            } catch (WorkspaceViolation | PatchRejected e) {
                return e.getMessage();
            }
        }
        return "the reply did not contain the updated file";
    }

    private static TraceStep step(
            List<TraceStep> trace,
            String kind,
            String name,
            String detail,
            long began,
            boolean error) {
        long millis = Duration.ofNanos(System.nanoTime() - began).toMillis();
        String shortDetail = detail.length() > 300 ? detail.substring(0, 300) : detail;
        return new TraceStep(trace.size() + 1, kind, name, shortDetail, millis, error);
    }
}
