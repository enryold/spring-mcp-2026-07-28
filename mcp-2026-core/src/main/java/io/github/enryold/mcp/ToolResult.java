package io.github.enryold.mcp;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class ToolResult {
    private static final String COMPLETE = "complete";
    private static final String INPUT_REQUIRED = "input_required";
    private static final String TASK = "task";
    private static final String DEFERRED = "deferred";

    private final ArrayNode content;
    private final JsonNode structuredContent;
    private final boolean hasStructuredContent;
    private final boolean error;
    private final ObjectNode metadata;
    private final String resultType;
    private final ObjectNode inputRequests;
    private final String requestState;
    private final ObjectNode taskFields;
    private final DeferredExecution deferredExecution;

    private ToolResult(
            ArrayNode content,
            JsonNode structuredContent,
            boolean hasStructuredContent,
            boolean error,
            ObjectNode metadata,
            String resultType,
            ObjectNode inputRequests,
            String requestState,
            ObjectNode taskFields,
            DeferredExecution deferredExecution) {
        this.content = Objects.requireNonNull(content, "content").deepCopy();
        this.structuredContent = structuredContent == null ? null : structuredContent.deepCopy();
        this.hasStructuredContent = hasStructuredContent;
        this.error = error;
        this.metadata = metadata == null ? null : metadata.deepCopy();
        this.resultType = Objects.requireNonNull(resultType, "resultType");
        this.inputRequests = inputRequests == null ? null : inputRequests.deepCopy();
        this.requestState = requestState;
        this.taskFields = taskFields == null ? null : taskFields.deepCopy();
        this.deferredExecution = deferredExecution;
    }

    public static ToolResult success(ArrayNode content) {
        return complete(content, null, false, false, null);
    }

    public static ToolResult success(ArrayNode content, JsonNode structuredContent) {
        return complete(content, Objects.requireNonNull(structuredContent, "structuredContent"), true, false, null);
    }

    public static ToolResult success(ArrayNode content, JsonNode structuredContent, ObjectNode metadata) {
        return complete(content, Objects.requireNonNull(structuredContent, "structuredContent"), true, false, metadata);
    }

    public static ToolResult successWithMetadata(ArrayNode content, ObjectNode metadata) {
        return complete(content, null, false, false, Objects.requireNonNull(metadata, "metadata"));
    }

    public static ToolResult error(ArrayNode content) {
        return complete(content, null, false, true, null);
    }

    public static ToolResult error(ArrayNode content, ObjectNode metadata) {
        return complete(content, null, false, true, metadata);
    }

    public static ToolResult errorWithStructuredContent(ArrayNode content, JsonNode structuredContent) {
        return complete(content, Objects.requireNonNull(structuredContent, "structuredContent"), true, true, null);
    }

    public static ToolResult error(ArrayNode content, JsonNode structuredContent, ObjectNode metadata) {
        return complete(content, Objects.requireNonNull(structuredContent, "structuredContent"), true, true, metadata);
    }

    public static ToolResult inputRequired(ObjectNode inputRequests) {
        if (Objects.requireNonNull(inputRequests, "inputRequests").isEmpty()) {
            throw new IllegalArgumentException("inputRequests must not be empty when requestState is absent");
        }
        return interaction(INPUT_REQUIRED, inputRequests, null, null, null);
    }

    public static ToolResult inputRequired(ObjectNode inputRequests, String requestState) {
        if (Objects.requireNonNull(inputRequests, "inputRequests").isEmpty() && blank(requestState)) {
            throw new IllegalArgumentException("input_required needs inputRequests or requestState");
        }
        return interaction(INPUT_REQUIRED, inputRequests, requestState, null, null);
    }

    public static ToolResult inputRequired(String requestState) {
        if (blank(requestState)) {
            throw new IllegalArgumentException("requestState must not be blank");
        }
        return interaction(INPUT_REQUIRED, null, requestState, null, null);
    }

    /** Defers the operation body until the interaction layer chooses synchronous or task execution. */
    public static ToolResult deferred(DeferredExecution execution) {
        return interaction(DEFERRED, null, null, null, Objects.requireNonNull(execution, "execution"));
    }

    public ArrayNode content() {
        return content.deepCopy();
    }

    public boolean hasStructuredContent() {
        return hasStructuredContent;
    }

    public JsonNode structuredContent() {
        return structuredContent == null ? null : structuredContent.deepCopy();
    }

    public boolean isError() {
        return error;
    }

    public ObjectNode metadata() {
        return metadata == null ? null : metadata.deepCopy();
    }

    public String resultType() {
        return resultType;
    }

    public ObjectNode inputRequests() {
        return inputRequests == null ? null : inputRequests.deepCopy();
    }

    public String requestState() {
        return requestState;
    }

    public ObjectNode taskFields() {
        return taskFields == null ? null : taskFields.deepCopy();
    }

    public ObjectNode toObjectNode() {
        if (DEFERRED.equals(resultType)) {
            throw new IllegalStateException("deferred result must be executed by McpInteractions");
        }
        ObjectNode wire = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("resultType", resultType);
        if (COMPLETE.equals(resultType)) {
            wire.set("content", content());
            if (hasStructuredContent) wire.set("structuredContent", structuredContent());
            if (error) wire.put("isError", true);
            if (metadata != null) wire.set("_meta", metadata());
        } else if (INPUT_REQUIRED.equals(resultType)) {
            if (inputRequests != null) wire.set("inputRequests", inputRequests());
            if (requestState != null) wire.put("requestState", requestState);
        } else if (TASK.equals(resultType)) {
            taskFields.properties().forEach(entry -> wire.set(entry.getKey(), entry.getValue().deepCopy()));
        }
        return wire;
    }

    boolean isDeferred() {
        return deferredExecution != null;
    }

    ToolResult runDeferred(McpContext executionContext) throws Exception {
        return Objects.requireNonNull(deferredExecution.execute(
                Objects.requireNonNull(executionContext, "executionContext")), "deferred result");
    }

    static ToolResult task(ObjectNode taskFields) {
        Objects.requireNonNull(taskFields, "taskFields");
        if (taskFields.has("resultType")) throw new IllegalArgumentException("taskFields must not contain resultType");
        return interaction(TASK, null, null, taskFields, null);
    }

    private static ToolResult complete(ArrayNode content, JsonNode structuredContent, boolean hasStructuredContent,
            boolean error, ObjectNode metadata) {
        return new ToolResult(content, structuredContent, hasStructuredContent, error, metadata,
                COMPLETE, null, null, null, null);
    }

    private static ToolResult interaction(String resultType, ObjectNode inputRequests, String requestState,
            ObjectNode taskFields, DeferredExecution execution) {
        return new ToolResult(tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode(), null, false, false, null,
                resultType, inputRequests, requestState, taskFields, execution);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @FunctionalInterface
    public interface DeferredExecution {
        ToolResult execute(McpContext executionContext) throws Exception;
    }
}
