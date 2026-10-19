package dev.rook.agent.loop;

import dev.rook.agent.model.ModelClient;
import dev.rook.agent.model.ModelException;
import dev.rook.agent.model.ModelMessage;
import dev.rook.agent.model.ModelRequest;
import dev.rook.agent.model.ModelResponse;
import dev.rook.agent.model.PriceTable;
import dev.rook.agent.model.ToolCall;
import dev.rook.agent.model.Usage;
import dev.rook.agent.safety.PromptSanitizer;
import dev.rook.agent.tools.ToolContext;
import dev.rook.agent.tools.ToolRegistry;
import dev.rook.agent.tools.ToolResult;
import dev.rook.agent.workspace.Workspace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

public final class AgentLoop {

    private static final int MAX_OUTPUT_TOKENS = 4_096;
    private static final int EXPLORE_LIMIT = 6;
    private static final String EDIT_TOOL = "apply_edit";

    private final ModelClient model;
    private final PriceTable prices;
    private final ToolRegistry tools;
    private final SandboxPort sandbox;
    private final Clock clock;
    private final AgentBudget budget;
    private final PromptSanitizer sanitizer;
    private final AgentListener listener;

    public AgentLoop(
            ModelClient model,
            PriceTable prices,
            ToolRegistry tools,
            SandboxPort sandbox,
            Clock clock,
            AgentBudget budget,
            PromptSanitizer sanitizer,
            AgentListener listener) {
        this.model = model;
        this.prices = prices;
        this.tools = tools;
        this.sandbox = sandbox;
        this.clock = clock;
        this.budget = budget;
        this.sanitizer = sanitizer;
        this.listener = listener;
    }

    private static final class Run {
        final List<ModelMessage> messages = new ArrayList<>();
        final List<TraceStep> trace = new ArrayList<>();
        Usage usage = Usage.NONE;
        long cost;
        int iterations;
        int repairRounds;
        int toolCalls;
        int exploring;
        boolean edited;
        String rejection = "";
    }

    private record Finish(
            String summary, double confidence, List<String> unresolved, List<String> risks) {}

    public AgentResult run(AgentTask task, Workspace workspace) {
        ToolContext context =
                new ToolContext(workspace, task.index(), task.report(), task.notes(), sanitizer);
        Run run = new Run();
        run.messages.add(
                ModelMessage.user(
                        AgentPrompts.task(
                                task.repository(),
                                task.events(),
                                task.report().impacts(),
                                task.preface())));
        Instant started = clock.instant();
        for (int iteration = 1; iteration <= budget.maxIterations(); iteration++) {
            Optional<AgentResult.Status> exhausted = exhausted(run, started);
            if (exhausted.isPresent()) {
                return stop(
                        run,
                        workspace,
                        exhausted.get(),
                        "the " + exhausted.get() + " limit was reached");
            }
            run.iterations = iteration;
            ModelResponse response;
            long began = System.nanoTime();
            try {
                response =
                        model.complete(
                                new ModelRequest(
                                        AgentPrompts.SYSTEM,
                                        run.messages,
                                        tools.definitions(),
                                        MAX_OUTPUT_TOKENS,
                                        0));
            } catch (ModelException e) {
                trace(run, "model", model.modelId(), e.getMessage(), began, true);
                return stop(run, workspace, AgentResult.Status.MODEL_FAILED, e.getMessage());
            }
            long cost = price(response);
            run.usage = run.usage.plus(response.usage());
            run.cost += cost;
            trace(
                    run,
                    "model",
                    response.model(),
                    response.usage().inputTokens()
                            + " in, "
                            + response.usage().outputTokens()
                            + " out",
                    began,
                    false);
            listener.modelCalled(iteration, response, cost);
            run.messages.add(response.asMessage());
            if (!response.wantsTools()) {
                return stop(
                        run,
                        workspace,
                        AgentResult.Status.STOPPED_WITHOUT_FINISH,
                        response.text().isBlank()
                                ? "the model stopped without calling finish"
                                : response.text());
            }
            Optional<AgentResult> done = handleCalls(run, response, context, workspace);
            if (done.isPresent()) {
                return done.get();
            }
        }
        return stop(
                run,
                workspace,
                AgentResult.Status.ITERATION_LIMIT,
                "the iteration limit of " + budget.maxIterations() + " was reached");
    }

    private Optional<AgentResult> handleCalls(
            Run run, ModelResponse response, ToolContext context, Workspace workspace) {
        int handled = 0;
        for (ToolCall call : response.toolCalls()) {
            if (run.toolCalls >= budget.maxToolCalls()) {
                run.messages.add(ModelMessage.toolResult(call.id(), "tool call limit reached"));
                return Optional.of(
                        stop(
                                run,
                                workspace,
                                AgentResult.Status.TOOL_CALL_LIMIT,
                                "the limit of "
                                        + budget.maxToolCalls()
                                        + " tool calls was reached"));
            }
            if (handled++ >= budget.maxToolCallsPerTurn()) {
                run.messages.add(
                        ModelMessage.toolResult(
                                call.id(),
                                "ignored: at most "
                                        + budget.maxToolCallsPerTurn()
                                        + " tool calls per turn"));
                continue;
            }
            run.toolCalls++;
            long began = System.nanoTime();
            ToolResult result = tools.execute(call, context);
            trace(
                    run,
                    "tool",
                    call.name(),
                    summary(call.arguments())
                            + (result.error() ? " => " + shorten(result.text()) : ""),
                    began,
                    result.error());
            listener.toolCalled(call, result);
            if (call.name().equals(FinishTool.NAME) && !result.error()) {
                Optional<AgentResult> verdict = judgeFinish(run, call, workspace);
                if (verdict.isPresent()) {
                    return verdict;
                }
                run.messages.add(ModelMessage.toolResult(call.id(), run.rejection));
                continue;
            }
            String text = result.text();
            if (call.name().equals(EDIT_TOOL) && !result.error()) {
                run.edited = true;
                run.exploring = 0;
            } else if (!run.edited
                    && !call.name().equals(RunTestsTool.NAME)
                    && !call.name().equals(FinishTool.NAME)) {
                run.exploring++;
            }
            if (!run.edited && run.exploring >= EXPLORE_LIMIT) {
                text = text + "\n\n" + AgentPrompts.explorationReminder(run.exploring);
            }
            run.messages.add(ModelMessage.toolResult(call.id(), text));
        }
        return Optional.empty();
    }

    private Optional<AgentResult> judgeFinish(Run run, ToolCall call, Workspace workspace) {
        Finish finish = parse(call.arguments());
        List<String> unresolved = new ArrayList<>(finish.unresolved());
        if (workspace.changedPaths().isEmpty()) {
            return Optional.of(
                    result(run, workspace, AgentResult.Status.NO_CHANGES, finish, unresolved));
        }
        if (sandbox == null) {
            unresolved.add("the tests were not run");
            return Optional.of(
                    result(run, workspace, AgentResult.Status.UNVERIFIED, finish, unresolved));
        }
        long began = System.nanoTime();
        TestRun tests = sandbox.run(workspace.root(), "all");
        trace(
                run,
                "tests",
                "all",
                tests.describe().lines().findFirst().orElse(""),
                began,
                !tests.passed());
        listener.testsRun(tests);
        if (tests.passed()) {
            return Optional.of(
                    result(run, workspace, AgentResult.Status.VERIFIED, finish, unresolved));
        }
        if (run.repairRounds >= budget.maxRepairRounds()) {
            unresolved.add(
                    "verification still fails: " + tests.describe().lines().findFirst().orElse(""));
            return Optional.of(
                    result(run, workspace, AgentResult.Status.TESTS_FAILING, finish, unresolved));
        }
        run.repairRounds++;
        run.rejection =
                AgentPrompts.testFailure(
                        run.repairRounds,
                        budget.maxRepairRounds(),
                        sanitizer.prepare("test-output", tests.describe()));
        return Optional.empty();
    }

    private static Finish parse(JsonNode arguments) {
        return new Finish(
                arguments.path("summary").asString(""),
                arguments.path("confidence").asDouble(0),
                strings(arguments.path("unresolved")),
                strings(arguments.path("risks")));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(item -> out.add(item.asString()));
        return out;
    }

    private Optional<AgentResult.Status> exhausted(Run run, Instant started) {
        if (run.usage.totalTokens() >= budget.maxTokens()) {
            return Optional.of(AgentResult.Status.TOKEN_BUDGET);
        }
        if (run.cost > budget.maxCostMicros()) {
            return Optional.of(AgentResult.Status.COST_BUDGET);
        }
        if (Duration.between(started, clock.instant()).compareTo(budget.wallClock()) >= 0) {
            return Optional.of(AgentResult.Status.TIME_BUDGET);
        }
        return Optional.empty();
    }

    private long price(ModelResponse response) {
        return prices.knows(response.model())
                ? prices.costMicros(response.model(), response.usage())
                : 0;
    }

    private void trace(
            Run run, String kind, String name, String detail, long beganNanos, boolean error) {
        long millis = Duration.ofNanos(System.nanoTime() - beganNanos).toMillis();
        String shortDetail = detail.length() > 300 ? detail.substring(0, 300) : detail;
        run.trace.add(new TraceStep(run.trace.size() + 1, kind, name, shortDetail, millis, error));
    }

    private static String shorten(String text) {
        String flat = text.replace('\n', ' ').strip();
        return flat.length() > 300 ? flat.substring(0, 300) : flat;
    }

    private static String summary(JsonNode arguments) {
        String text = arguments.toString();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private AgentResult stop(
            Run run, Workspace workspace, AgentResult.Status status, String reason) {
        Finish finish = new Finish(reason, 0, List.of(), List.of());
        return result(run, workspace, status, finish, List.of(reason));
    }

    private AgentResult result(
            Run run,
            Workspace workspace,
            AgentResult.Status status,
            Finish finish,
            List<String> unresolved) {
        return new AgentResult(
                status,
                finish.summary(),
                finish.confidence(),
                unresolved,
                finish.risks(),
                workspace.diff(),
                workspace.changedPaths(),
                run.usage,
                run.cost,
                run.iterations,
                run.repairRounds,
                run.trace);
    }
}
