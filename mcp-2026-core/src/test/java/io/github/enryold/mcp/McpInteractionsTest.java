package io.github.enryold.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class McpInteractionsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void validatesAndBindsMultiRoundTripStateAndCachesReplay() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(8, 8), executor);
            AtomicInteger effects = new AtomicInteger();
            McpTool tool = tool("approval", "forbidden", (arguments, context) -> {
                ObjectNode responses = object(context.requestParams().get("inputResponses"));
                if (responses == null || !responses.has("approval")) {
                    return ToolResult.inputRequired(inputRequests("approval"), "private-handler-state");
                }
                effects.incrementAndGet();
                assertThat(context.requestParams().path("requestState").asText()).isEqualTo("private-handler-state");
                assertThat(responses.has("ignored")).isFalse();
                return text("approved");
            });
            ObjectNode arguments = object().put("item", "one");

            ToolResult first = interactions.execute(tool, arguments, context("alice", "red", "tools/call",
                    callParams("approval", arguments), capabilities("elicitation")));
            String state = first.requestState();
            assertThat(first.resultType()).isEqualTo("input_required");
            assertThat(state).isNotBlank().isNotEqualTo("private-handler-state");

            ObjectNode wrong = callParams("approval", arguments).put("requestState", state);
            wrong.set("inputResponses", object().set("ignored", object().put("action", "accept")));
            ToolResult pending = interactions.execute(tool, arguments,
                    context("alice", "red", "tools/call", wrong, capabilities("elicitation")));
            assertThat(pending.resultType()).isEqualTo("input_required");
            assertThat(effects).hasValue(0);

            ObjectNode invalidContent = callParams("approval", arguments).put("requestState", state);
            invalidContent.set("inputResponses", object().set("approval",
                    object().put("action", "accept").set("content", object().put("value", 42))));
            ToolResult invalidPending = interactions.execute(tool, arguments,
                    context("alice", "red", "tools/call", invalidContent, capabilities("elicitation")));
            assertThat(invalidPending.inputRequests().has("approval")).isTrue();
            assertThat(effects).hasValue(0);

            assertThatThrownBy(() -> interactions.execute(tool, arguments,
                    context("mallory", "red", "tools/call", validRetry("approval", arguments, state),
                            capabilities("elicitation"))))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32602));

            McpContext retry = context("alice", "red", "tools/call", validRetry("approval", arguments, state),
                    capabilities("elicitation"));
            ToolResult completed = interactions.execute(tool, arguments, retry);
            ToolResult replayed = interactions.execute(tool, arguments, retry);
            assertThat(completed.toObjectNode()).isEqualTo(replayed.toObjectNode());
            assertThat(effects).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsUnboundInputResponsesBeforeInvokingToolPromptOrResourceHandlers() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(8, 2), executor);
            ObjectNode forgedResponses = object().set("approval", elicitResponse("forged"));
            ObjectNode arguments = object();
            AtomicInteger toolCalls = new AtomicInteger();
            McpTool tool = tool("forged-tool", "forbidden", (ignored, context) -> {
                toolCalls.incrementAndGet();
                assertThat(context.requestParams().has("inputResponses")).isFalse();
                return text("invoked");
            });
            ObjectNode toolParams = callParams("forged-tool", arguments).set("inputResponses", forgedResponses);
            assertThatThrownBy(() -> interactions.execute(tool, arguments,
                    context("alice", "red", "tools/call", toolParams, capabilities("elicitation"))))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32602));
            assertThat(toolCalls).hasValue(0);

            for (String method : List.of("prompts/get", "resources/read")) {
                AtomicInteger calls = new AtomicInteger();
                String name = method.equals("prompts/get") ? "forged-prompt" : "test://forged-resource";
                ObjectNode salient = object().put("name", name);
                ObjectNode params = salient.deepCopy().set("inputResponses", forgedResponses.deepCopy());
                assertThatThrownBy(() -> interactions.execute(method, name, salient,
                        context("alice", "red", method, params, capabilities("elicitation")), execution -> {
                            calls.incrementAndGet();
                            return object().put("resultType", "complete");
                        }))
                        .isInstanceOfSatisfying(McpProtocolException.class,
                                error -> assertThat(error.code()).isEqualTo(-32602));
                assertThat(calls).hasValue(0);

                ObjectNode empty = salient.deepCopy().set("inputResponses", object());
                interactions.execute(method, name, salient,
                        context("alice", "red", method, empty, capabilities()), execution -> {
                            calls.incrementAndGet();
                            assertThat(execution.requestParams().has("inputResponses")).isFalse();
                            return object().put("resultType", "complete");
                        });
                assertThat(calls).hasValue(1);
            }

            ObjectNode emptyToolParams = callParams("forged-tool", arguments).set("inputResponses", object());
            assertThat(interactions.execute(tool, arguments,
                    context("alice", "red", "tools/call", emptyToolParams, capabilities())).resultType())
                    .isEqualTo("complete");
            assertThat(toolCalls).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void internalRequestTransformsPreserveTheActiveProgressSequence() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(4, 2), executor);
            ObjectNode arguments = object();
            AtomicInteger notifications = new AtomicInteger();
            McpTool sanitized = tool("sanitized-progress", "forbidden", (ignored, context) -> {
                assertThatThrownBy(() -> context.emitNotification(progress("progress", 1)))
                        .isInstanceOf(IllegalArgumentException.class);
                return text("done");
            });
            ObjectNode sanitizedParams = callParams("sanitized-progress", arguments)
                    .set("inputResponses", object());
            McpContext sanitizedContext = contextWithProgress("tools/call", sanitizedParams, capabilities(), notifications);
            sanitizedContext.emitNotification(progress("progress", 2));
            assertThat(interactions.execute(sanitized, arguments, sanitizedContext).resultType()).isEqualTo("complete");
            assertThat(notifications).hasValue(1);

            McpTool resumed = tool("resumed-progress", "forbidden", (ignored, context) -> {
                if (object(context.requestParams().get("inputResponses")) == null) {
                    return ToolResult.inputRequired(inputRequests("approval"));
                }
                assertThatThrownBy(() -> context.emitNotification(progress("progress", 1)))
                        .isInstanceOf(IllegalArgumentException.class);
                return text("done");
            });
            ToolResult first = interactions.execute(resumed, arguments, context("alice", "red", "tools/call",
                    callParams("resumed-progress", arguments), capabilities("elicitation")));
            ObjectNode retry = validRetry("resumed-progress", arguments, first.requestState());
            McpContext retryContext = contextWithProgress("tools/call", retry, capabilities("elicitation"), notifications);
            retryContext.emitNotification(progress("progress", 2));
            assertThat(interactions.execute(resumed, arguments, retryContext).resultType()).isEqualTo("complete");
            assertThat(notifications).hasValue(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createsStronglyVisiblePrincipalBoundTaskAndPersistsValidatedCompletion() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(8, 8), executor);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger effects = new AtomicInteger();
            McpTool tool = tool("slow", "optional", (arguments, context) -> ToolResult.deferred(executionContext -> {
                release.await(5, TimeUnit.SECONDS);
                effects.incrementAndGet();
                return text("done");
            }));
            ObjectNode arguments = object().put("label", "one");
            ObjectNode caps = capabilities(McpInteractions.TASKS_EXTENSION);
            McpContext call = context("alice", "red", "tools/call", callParams("slow", arguments), caps);

            ToolResult created = interactions.execute(tool, arguments, call, ToolResult::toObjectNode);
            String taskId = created.taskFields().path("taskId").asText();
            assertThat(created.resultType()).isEqualTo("task");
            assertThat(created.taskFields().path("status").asText()).isEqualTo("working");
            assertThat(taskId).isNotBlank();

            ObjectNode working = method(interactions, "tasks/get").handler().execute(
                    taskParams(taskId), context("alice", "red", "tasks/get", taskParams(taskId), caps));
            assertThat(working.path("status").asText()).isEqualTo("working");

            assertThatThrownBy(() -> method(interactions, "tasks/get").handler().execute(
                    taskParams(taskId), context("bob", "red", "tasks/get", taskParams(taskId), caps)))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32602));

            release.countDown();
            ObjectNode terminal = awaitStatus(interactions, taskId, "completed", caps);
            assertThat(terminal.path("result").path("content").get(0).path("text").asText()).isEqualTo("done");
            assertThat(effects).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void partialTaskResponsesRemainPendingAndUnknownKeysAreIgnored() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(8, 8), executor);
            McpTool tool = tool("multi", "required", (arguments, context) -> {
                ObjectNode responses = object(context.requestParams().get("inputResponses"));
                if (responses == null || !responses.has("first") || !responses.has("second")) {
                    return ToolResult.deferred(executionContext -> ToolResult.inputRequired(twoInputRequests(), "round-one"));
                }
                return ToolResult.deferred(executionContext -> text("complete"));
            });
            ObjectNode caps = capabilities("elicitation", McpInteractions.TASKS_EXTENSION);
            ObjectNode arguments = object();
            McpContext call = context("alice", "red", "tools/call", callParams("multi", arguments), caps);
            String taskId = interactions.execute(tool, arguments, call).taskFields().path("taskId").asText();
            awaitStatus(interactions, taskId, "input_required", caps);

            ObjectNode firstUpdate = taskParams(taskId);
            firstUpdate.set("inputResponses", object()
                    .set("first", elicitResponse("one"))
                    .set("unknown", object().put("action", "accept")));
            ObjectNode ack = method(interactions, "tasks/update").handler().execute(firstUpdate,
                    context("alice", "red", "tasks/update", firstUpdate, caps));
            assertThat(ack).isEqualTo(object().put("resultType", "complete"));

            ObjectNode partial = get(interactions, taskId, caps);
            assertThat(partial.path("status").asText()).isEqualTo("input_required");
            assertThat(partial.path("inputRequests").has("first")).isFalse();
            assertThat(partial.path("inputRequests").has("second")).isTrue();

            ObjectNode secondUpdate = taskParams(taskId);
            secondUpdate.set("inputResponses", object().set("second", elicitResponse("two")));
            method(interactions, "tasks/update").handler().execute(secondUpdate,
                    context("alice", "red", "tasks/update", secondUpdate, caps));
            assertThat(awaitStatus(interactions, taskId, "completed", caps).path("status").asText())
                    .isEqualTo("completed");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void requiredTaskCapabilityFailsBeforeHandlerExecution() {
        var executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger calls = new AtomicInteger();
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(2, 2), executor);
            McpTool tool = tool("required", "required", (arguments, context) -> {
                calls.incrementAndGet();
                return text("never");
            });
            ObjectNode arguments = object();
            assertThatThrownBy(() -> interactions.execute(tool, arguments,
                    context("alice", "red", "tools/call", callParams("required", arguments), capabilities())))
                    .isInstanceOfSatisfying(McpProtocolException.class, error -> {
                        assertThat(error.code()).isEqualTo(-32021);
                        assertThat(error.data().path("requiredCapabilities").path("extensions")
                                .has(McpInteractions.TASKS_EXTENSION)).isTrue();
                    });
            assertThat(calls).hasValue(0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void elicitationModeMustMatchTheDeclaredCapability() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(4, 2), executor);
            McpTool form = tool("form", "forbidden",
                    (arguments, context) -> ToolResult.inputRequired(inputRequests("approval")));
            McpTool url = tool("url", "forbidden", (arguments, context) -> ToolResult.inputRequired(object().set(
                    "approval", object().put("method", "elicitation/create").set("params", object()
                            .put("mode", "url").put("message", "Open approval page")
                            .put("url", "https://example.test/approve")))));
            ObjectNode arguments = object();

            assertThat(interactions.execute(form, arguments, context("alice", "red", "tools/call",
                    callParams("form", arguments), capabilities("elicitation"))).resultType())
                    .isEqualTo("input_required");

            ObjectNode urlOnly = object().set("elicitation", object().set("url", object()));
            assertThatThrownBy(() -> interactions.execute(form, arguments, context("alice", "red", "tools/call",
                    callParams("form", arguments), urlOnly)))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32021));

            ObjectNode formOnly = object().set("elicitation", object().set("form", object()));
            assertThatThrownBy(() -> interactions.execute(url, arguments, context("alice", "red", "tools/call",
                    callParams("url", arguments), formOnly)))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32021));

            assertThat(interactions.execute(url, arguments, context("alice", "red", "tools/call",
                    callParams("url", arguments), urlOnly)).resultType()).isEqualTo("input_required");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void taskUsesDetachedLifecycleAndNeverWritesToCompletedHttpSink() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(2, 2), executor);
            AtomicBoolean cancelled = new AtomicBoolean(true);
            AtomicInteger httpNotifications = new AtomicInteger();
            CountDownLatch ran = new CountDownLatch(1);
            McpTool tool = tool("detached", "optional", (arguments, context) -> ToolResult.deferred(execution -> {
                cancelled.set(execution.isCancelled());
                execution.emitNotification(object().put("jsonrpc", "2.0")
                        .put("method", "notifications/message").set("params", object()
                                .put("level", "info").put("data", "detached")));
                ran.countDown();
                return text("done");
            }));
            ObjectNode arguments = object();
            ObjectNode caps = capabilities(McpInteractions.TASKS_EXTENSION);
            ObjectNode params = callParams("detached", arguments);
            ObjectNode metadata = object()
                    .put("io.modelcontextprotocol/protocolVersion", McpEngine.PROTOCOL_VERSION)
                    .set("io.modelcontextprotocol/clientCapabilities", caps.deepCopy());
            metadata.set("io.modelcontextprotocol/clientInfo", object().put("name", "test").put("version", "1"));
            McpContext requestContext = new McpContext("alice", "red", Set.of("read"), Map.of(),
                    Instant.MAX, () -> true).withRequest(
                            new McpRequest(JSON.getNodeFactory().numberNode(1), "tools/call", params, metadata),
                            ignored -> httpNotifications.incrementAndGet());

            String taskId = interactions.execute(tool, arguments, requestContext).taskFields().path("taskId").asText();
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitStatus(interactions, taskId, "completed", caps).path("status").asText())
                    .isEqualTo("completed");
            assertThat(cancelled).isFalse();
            assertThat(httpNotifications).hasValue(0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedTaskSubmissionDoesNotLeaveAnUnreachableStoredTask() {
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();
        McpInteractionStore store = new McpInteractionStore(2, 2);
        McpInteractions interactions = new McpInteractions(store, executor);
        McpTool tool = tool("rejected", "optional",
                (arguments, context) -> ToolResult.deferred(execution -> text("never")));
        ObjectNode arguments = object();

        assertThatThrownBy(() -> interactions.execute(tool, arguments,
                context("alice", "red", "tools/call", callParams("rejected", arguments),
                        capabilities(McpInteractions.TASKS_EXTENSION))))
                .isInstanceOfSatisfying(McpProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(-32000));
        assertThat(store.taskCount()).isZero();
    }

    @Test
    void cachedInteractionResultsRevalidateCurrentRequestCapabilities() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            McpInteractionStore store = new McpInteractionStore(8, 8);
            McpInteractions interactions = new McpInteractions(store, executor);
            AtomicInteger handlerCalls = new AtomicInteger();
            AtomicInteger taskExecutions = new AtomicInteger();
            McpTool taskTool = tool("optional-task", "optional", (arguments, context) -> {
                handlerCalls.incrementAndGet();
                if (object(context.requestParams().get("inputResponses")) == null) {
                    return ToolResult.inputRequired(inputRequests("approval"));
                }
                return ToolResult.deferred(execution -> {
                    taskExecutions.incrementAndGet();
                    release.await(5, TimeUnit.SECONDS);
                    return text("done");
                });
            });
            ObjectNode arguments = object();
            ToolResult first = interactions.execute(taskTool, arguments, context("alice", "red", "tools/call",
                    callParams("optional-task", arguments), capabilities("elicitation")));
            ObjectNode retryParams = validRetry("optional-task", arguments, first.requestState());
            ObjectNode allCapabilities = capabilities("elicitation", McpInteractions.TASKS_EXTENSION);
            ToolResult task = interactions.execute(taskTool, arguments,
                    context("alice", "red", "tools/call", retryParams, allCapabilities));

            assertThatThrownBy(() -> interactions.execute(taskTool, arguments,
                    context("alice", "red", "tools/call", retryParams, capabilities("elicitation"))))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32021));
            assertThat(interactions.execute(taskTool, arguments,
                    context("alice", "red", "tools/call", retryParams, allCapabilities)).toObjectNode())
                    .isEqualTo(task.toObjectNode());
            assertThat(handlerCalls).hasValue(2);
            assertThat(store.taskCount()).isEqualTo(1);

            AtomicInteger secondRoundEffects = new AtomicInteger();
            McpTool multiRound = tool("multi-round", "forbidden", (ignored, context) -> {
                ObjectNode responses = object(context.requestParams().get("inputResponses"));
                if (responses == null || !responses.has("first")) {
                    return ToolResult.inputRequired(inputRequests("first"));
                }
                secondRoundEffects.incrementAndGet();
                return ToolResult.inputRequired(inputRequests("second"));
            });
            ToolResult roundOne = interactions.execute(multiRound, arguments, context("alice", "red", "tools/call",
                    callParams("multi-round", arguments), capabilities("elicitation")));
            ObjectNode roundOneRetry = callParams("multi-round", arguments).put("requestState", roundOne.requestState());
            roundOneRetry.set("inputResponses", object().set("first", elicitResponse("one")));
            ToolResult roundTwo = interactions.execute(multiRound, arguments,
                    context("alice", "red", "tools/call", roundOneRetry, capabilities("elicitation")));

            assertThatThrownBy(() -> interactions.execute(multiRound, arguments,
                    context("alice", "red", "tools/call", roundOneRetry, capabilities())))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32021));
            assertThat(interactions.execute(multiRound, arguments,
                    context("alice", "red", "tools/call", roundOneRetry, capabilities("elicitation"))).toObjectNode())
                    .isEqualTo(roundTwo.toObjectNode());
            assertThat(secondRoundEffects).hasValue(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cachedNonToolInputRequiredRevalidatesCurrentRequestCapabilities() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(8, 2), executor);
            AtomicInteger effects = new AtomicInteger();
            ObjectNode salient = object().put("name", "prompt");
            McpInteractions.ObjectInvocation invocation = context -> {
                ObjectNode responses = object(context.requestParams().get("inputResponses"));
                if (responses == null || !responses.has("first")) {
                    return object().put("resultType", "input_required")
                            .set("inputRequests", inputRequests("first"));
                }
                effects.incrementAndGet();
                return object().put("resultType", "input_required")
                        .set("inputRequests", inputRequests("second"));
            };
            ObjectNode initialParams = salient.deepCopy();
            ObjectNode first = interactions.execute("prompts/get", "prompt", salient,
                    context("alice", "red", "prompts/get", initialParams, capabilities("elicitation")), invocation);
            ObjectNode retry = salient.deepCopy().put("requestState", first.path("requestState").asText());
            retry.set("inputResponses", object().set("first", elicitResponse("one")));
            ObjectNode second = interactions.execute("prompts/get", "prompt", salient,
                    context("alice", "red", "prompts/get", retry, capabilities("elicitation")), invocation);

            assertThatThrownBy(() -> interactions.execute("prompts/get", "prompt", salient,
                    context("alice", "red", "prompts/get", retry, capabilities()), invocation))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32021));
            assertThat(interactions.execute("prompts/get", "prompt", salient,
                    context("alice", "red", "prompts/get", retry, capabilities("elicitation")), invocation))
                    .isEqualTo(second);
            assertThat(effects).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oversizedAcceptedResponsesAreRejectedAtomicallyForContinuationsAndTasks() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(8, 8), executor);
            McpTool continuationTool = tool("bounded", "forbidden", (arguments, context) -> {
                ObjectNode responses = object(context.requestParams().get("inputResponses"));
                if (responses == null || !responses.has("first") || !responses.has("second")) {
                    return ToolResult.inputRequired(twoInputRequests());
                }
                return text("complete");
            });
            ObjectNode arguments = object();
            ObjectNode caps = capabilities("elicitation");
            ToolResult first = interactions.execute(continuationTool, arguments, context("alice", "red", "tools/call",
                    callParams("bounded", arguments), caps));
            ObjectNode oversized = callParams("bounded", arguments).put("requestState", first.requestState());
            oversized.set("inputResponses", object()
                    .set("first", elicitResponse("x".repeat(McpInteractions.MAX_ACCEPTED_RESPONSE_BYTES)))
                    .set("second", elicitResponse("two")));
            assertThatThrownBy(() -> interactions.execute(continuationTool, arguments,
                    context("alice", "red", "tools/call", oversized, caps)))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32602));

            ObjectNode onlyFirst = callParams("bounded", arguments).put("requestState", first.requestState());
            onlyFirst.set("inputResponses", object().set("first", elicitResponse("one")));
            ToolResult pending = interactions.execute(continuationTool, arguments,
                    context("alice", "red", "tools/call", onlyFirst, caps));
            assertThat(pending.resultType()).isEqualTo("input_required");
            assertThat(pending.inputRequests().has("second")).isTrue();

            McpTool taskTool = tool("bounded-task", "required",
                    (ignored, context) -> ToolResult.deferred(execution -> ToolResult.inputRequired(twoInputRequests())));
            ObjectNode taskCaps = capabilities("elicitation", McpInteractions.TASKS_EXTENSION);
            String taskId = interactions.execute(taskTool, arguments, context("alice", "red", "tools/call",
                    callParams("bounded-task", arguments), taskCaps)).taskFields().path("taskId").asText();
            awaitStatus(interactions, taskId, "input_required", taskCaps);
            ObjectNode update = taskParams(taskId);
            update.set("inputResponses", oversized.path("inputResponses").deepCopy());
            assertThatThrownBy(() -> method(interactions, "tasks/update").handler().execute(update,
                    context("alice", "red", "tasks/update", update, taskCaps)))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32602));
            ObjectNode unchanged = get(interactions, taskId, taskCaps);
            assertThat(unchanged.path("inputRequests").has("first")).isTrue();
            assertThat(unchanged.path("inputRequests").has("second")).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void taskInputKeyBudgetBoundsTheNumberOfRounds() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractionStore store = new McpInteractionStore(2, 2);
            McpInteractions interactions = new McpInteractions(store, executor);
            McpTool tool = tool("round-budget", "required", (arguments, context) -> {
                ObjectNode responses = object(context.requestParams().get("inputResponses"));
                return ToolResult.deferred(execution -> responses == null
                        ? ToolResult.inputRequired(numberedInputRequests(0, McpInteractions.MAX_TASK_INPUT_KEYS))
                        : ToolResult.inputRequired(numberedInputRequests(McpInteractions.MAX_TASK_INPUT_KEYS, 1)));
            });
            ObjectNode caps = capabilities("elicitation", McpInteractions.TASKS_EXTENSION);
            ObjectNode arguments = object();
            String taskId = interactions.execute(tool, arguments, context("alice", "red", "tools/call",
                    callParams("round-budget", arguments), caps)).taskFields().path("taskId").asText();
            awaitStatus(interactions, taskId, "input_required", caps);
            ObjectNode responses = object();
            for (int index = 0; index < McpInteractions.MAX_TASK_INPUT_KEYS; index++) {
                responses.set("key-" + index, elicitResponse("value"));
            }
            ObjectNode update = taskParams(taskId).set("inputResponses", responses);
            method(interactions, "tasks/update").handler().execute(update,
                    context("alice", "red", "tasks/update", update, caps));
            ObjectNode failed = awaitStatus(interactions, taskId, "failed", caps);
            assertThat(failed.path("error").path("code").asInt()).isEqualTo(-32602);
            assertThat(failed.path("error").path("message").asText()).contains("key budget");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationSeesAndInterruptsTheNextTaskFutureBeforeExecutorReturns() throws Exception {
        BlockingSecondExecutionExecutor executor = new BlockingSecondExecutionExecutor();
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch handlerRelease = new CountDownLatch(1);
        CountDownLatch handlerInterrupted = new CountDownLatch(1);
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(2, 2), executor);
            AtomicInteger postWaitEffects = new AtomicInteger();
            McpTool tool = tool("cancel-race", "required", (arguments, context) -> {
                if (object(context.requestParams().get("inputResponses")) == null) {
                    return ToolResult.deferred(execution -> ToolResult.inputRequired(inputRequests("approval")));
                }
                handlerEntered.countDown();
                try {
                    handlerRelease.await();
                    postWaitEffects.incrementAndGet();
                    return text("unexpected");
                } catch (InterruptedException error) {
                    handlerInterrupted.countDown();
                    throw error;
                }
            });
            ObjectNode caps = capabilities("elicitation", McpInteractions.TASKS_EXTENSION);
            ObjectNode arguments = object();
            String taskId = interactions.execute(tool, arguments, context("alice", "red", "tools/call",
                    callParams("cancel-race", arguments), caps)).taskFields().path("taskId").asText();
            awaitStatus(interactions, taskId, "input_required", caps);

            ObjectNode update = taskParams(taskId).set("inputResponses",
                    object().set("approval", elicitResponse("yes")));
            AtomicReference<Throwable> updateFailure = new AtomicReference<>();
            Thread updater = new Thread(() -> {
                try {
                    method(interactions, "tasks/update").handler().execute(update,
                            context("alice", "red", "tasks/update", update, caps));
                } catch (Throwable error) {
                    updateFailure.set(error);
                }
            });
            updater.start();
            assertThat(executor.secondExecuteEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(handlerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            ObjectNode cancel = taskParams(taskId);
            method(interactions, "tasks/cancel").handler().execute(cancel,
                    context("alice", "red", "tasks/cancel", cancel, caps));
            assertThat(handlerInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
            executor.releaseSecondExecute.countDown();
            updater.join(5_000);

            assertThat(updateFailure.get()).isNull();
            assertThat(postWaitEffects).hasValue(0);
            assertThat(get(interactions, taskId, caps).path("status").asText()).isEqualTo("cancelled");
        } finally {
            handlerRelease.countDown();
            executor.releaseSecondExecute.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void taskPollRevalidatesCapabilitiesWithoutConsumingPendingInput() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpInteractions interactions = new McpInteractions(new McpInteractionStore(2, 2), executor);
            McpTool tool = tool("poll-capability", "required",
                    (arguments, context) -> ToolResult.deferred(execution -> ToolResult.inputRequired(inputRequests("approval"))));
            ObjectNode allCapabilities = capabilities("elicitation", McpInteractions.TASKS_EXTENSION);
            ObjectNode arguments = object();
            String taskId = interactions.execute(tool, arguments, context("alice", "red", "tools/call",
                    callParams("poll-capability", arguments), allCapabilities)).taskFields().path("taskId").asText();
            awaitStatus(interactions, taskId, "input_required", allCapabilities);

            ObjectNode params = taskParams(taskId);
            assertThatThrownBy(() -> method(interactions, "tasks/get").handler().execute(params,
                    context("alice", "red", "tasks/get", params,
                            capabilities(McpInteractions.TASKS_EXTENSION))))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32021));
            ObjectNode capablePoll = get(interactions, taskId, allCapabilities);
            assertThat(capablePoll.path("status").asText()).isEqualTo("input_required");
            assertThat(capablePoll.path("inputRequests").has("approval")).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredRunningTaskStaysChargedUntilItsRunnerActuallyExits() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            McpInteractionStore store = new McpInteractionStore(2, 1);
            McpInteractions interactions = new McpInteractions(store, executor, clock,
                    Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofMillis(10));
            McpTool firstTool = tool("expiry-a", "required", (arguments, context) -> ToolResult.deferred(execution -> {
                entered.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        interrupted.countDown();
                    }
                }
                return text("finished");
            }));
            ObjectNode caps = capabilities(McpInteractions.TASKS_EXTENSION);
            ObjectNode arguments = object();
            String taskId = interactions.execute(firstTool, arguments, context("alice", "red", "tools/call",
                    callParams("expiry-a", arguments), caps)).taskFields().path("taskId").asText();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofSeconds(2));

            ObjectNode get = taskParams(taskId);
            assertThatThrownBy(() -> method(interactions, "tasks/get").handler().execute(get,
                    context("alice", "red", "tasks/get", get, caps)))
                    .isInstanceOfSatisfying(McpProtocolException.class,
                            error -> assertThat(error.code()).isEqualTo(-32602));
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();

            McpTool secondTool = tool("expiry-b", "required",
                    (ignored, context) -> ToolResult.deferred(execution -> text("done")));
            assertThatThrownBy(() -> interactions.execute(secondTool, arguments,
                    context("alice", "red", "tools/call", callParams("expiry-b", arguments), caps)))
                    .isInstanceOfSatisfying(McpProtocolException.class, error -> {
                        assertThat(error.code()).isEqualTo(-32000);
                        assertThat(error.httpStatus()).isEqualTo(503);
                    });
            assertThat(store.taskCount()).isEqualTo(1);

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (store.taskCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(store.taskCount()).isZero();
            assertThat(interactions.execute(secondTool, arguments,
                    context("alice", "red", "tools/call", callParams("expiry-b", arguments), caps)).resultType())
                    .isEqualTo("task");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static ObjectNode awaitStatus(McpInteractions interactions, String taskId, String status, ObjectNode caps)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ObjectNode current;
        do {
            current = get(interactions, taskId, caps);
            if (status.equals(current.path("status").asText())) return current;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("task did not reach " + status + ": " + current);
    }

    private static ObjectNode get(McpInteractions interactions, String taskId, ObjectNode caps) throws Exception {
        ObjectNode params = taskParams(taskId);
        return method(interactions, "tasks/get").handler().execute(params,
                context("alice", "red", "tasks/get", params, caps));
    }

    private static McpMethod method(McpInteractions interactions, String name) {
        return interactions.methods().stream().filter(method -> name.equals(method.name())).findFirst().orElseThrow();
    }

    private static McpTool tool(String name, String taskSupport, McpTool.Handler handler) {
        ObjectNode descriptor = object().put("name", name).put("description", name);
        descriptor.set("inputSchema", object().put("type", "object"));
        descriptor.putObject("execution").put("taskSupport", taskSupport);
        return new McpTool(descriptor, handler);
    }

    private static ToolResult text(String value) {
        ArrayNode content = JSON.createArrayNode().add(object().put("type", "text").put("text", value));
        return ToolResult.success(content);
    }

    private static ObjectNode inputRequests(String key) {
        return object().set(key, elicitRequest("Value for " + key));
    }

    private static ObjectNode twoInputRequests() {
        return object().set("first", elicitRequest("First")).set("second", elicitRequest("Second"));
    }

    private static ObjectNode numberedInputRequests(int first, int count) {
        ObjectNode requests = object();
        for (int index = first; index < first + count; index++) {
            requests.set("key-" + index, elicitRequest("Value"));
        }
        return requests;
    }

    private static ObjectNode elicitRequest(String message) {
        ObjectNode request = object().put("method", "elicitation/create");
        ObjectNode params = request.putObject("params").put("mode", "form").put("message", message);
        params.putObject("requestedSchema").put("type", "object").putObject("properties")
                .putObject("value").put("type", "string");
        return request;
    }

    private static ObjectNode elicitResponse(String value) {
        return object().put("action", "accept").set("content", object().put("value", value));
    }

    private static ObjectNode validRetry(String name, ObjectNode arguments, String state) {
        ObjectNode params = callParams(name, arguments).put("requestState", state);
        params.set("inputResponses", object().set("approval", elicitResponse("yes")));
        return params;
    }

    private static ObjectNode callParams(String name, ObjectNode arguments) {
        return object().put("name", name).set("arguments", arguments.deepCopy());
    }

    private static ObjectNode taskParams(String taskId) {
        return object().put("taskId", taskId);
    }

    private static McpContext context(String subject, String tenant, String method, ObjectNode params, ObjectNode caps) {
        ObjectNode metadata = object()
                .put("io.modelcontextprotocol/protocolVersion", McpEngine.PROTOCOL_VERSION)
                .set("io.modelcontextprotocol/clientCapabilities", caps.deepCopy());
        metadata.set("io.modelcontextprotocol/clientInfo", object().put("name", "test").put("version", "1"));
        ObjectNode fullParams = params.deepCopy().set("_meta", metadata.deepCopy());
        McpRequest request = new McpRequest(JSON.getNodeFactory().numberNode(1), method, fullParams, metadata);
        return new McpContext(subject, tenant, Set.of("read"), Map.of(), Instant.MAX, () -> false)
                .withRequest(request);
    }

    private static McpContext contextWithProgress(String method, ObjectNode params, ObjectNode caps,
            AtomicInteger notifications) {
        ObjectNode metadata = object()
                .put("io.modelcontextprotocol/protocolVersion", McpEngine.PROTOCOL_VERSION)
                .put("progressToken", "progress")
                .set("io.modelcontextprotocol/clientCapabilities", caps.deepCopy());
        metadata.set("io.modelcontextprotocol/clientInfo", object().put("name", "test").put("version", "1"));
        ObjectNode fullParams = params.deepCopy().set("_meta", metadata.deepCopy());
        return new McpContext("alice", "red", Set.of("read"), Map.of(), Instant.MAX, () -> false)
                .withRequest(new McpRequest(JSON.getNodeFactory().numberNode(1), method, fullParams, metadata),
                        ignored -> notifications.incrementAndGet());
    }

    private static ObjectNode progress(String token, double value) {
        return object().put("jsonrpc", "2.0").put("method", "notifications/progress")
                .set("params", object().put("progressToken", token).put("progress", value));
    }

    private static ObjectNode capabilities(String... names) {
        ObjectNode capabilities = object();
        for (String name : names) {
            if (name.contains("/")) capabilities.withObject("extensions").putObject(name);
            else capabilities.putObject(name);
        }
        return capabilities;
    }

    private static ObjectNode object(JsonNode value) {
        return value instanceof ObjectNode object ? object : null;
    }

    private static ObjectNode object() {
        return JSON.createObjectNode();
    }

    private static final class BlockingSecondExecutionExecutor extends AbstractExecutorService {
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private final java.util.concurrent.CopyOnWriteArrayList<Thread> workers =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch secondExecuteEntered = new CountDownLatch(1);
        final CountDownLatch releaseSecondExecute = new CountDownLatch(1);

        @Override
        public void execute(Runnable command) {
            int execution = executions.incrementAndGet();
            Thread worker = new Thread(command, "interaction-test-" + execution);
            workers.add(worker);
            worker.start();
            if (execution == 2) {
                secondExecuteEntered.countDown();
                try {
                    if (!releaseSecondExecute.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("second execute was not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
        }

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown.set(true);
            workers.forEach(Thread::interrupt);
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get() && workers.stream().noneMatch(Thread::isAlive);
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            for (Thread worker : workers) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            return isTerminated();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!getZone().equals(zone)) throw new IllegalArgumentException("test clock is fixed to UTC");
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
