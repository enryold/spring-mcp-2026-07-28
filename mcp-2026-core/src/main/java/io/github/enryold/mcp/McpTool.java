package io.github.enryold.mcp;

import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

public final class McpTool {
    private final ObjectNode descriptor;
    private final Handler handler;
    private final ObjectNode requiredClientCapabilities;
    private final boolean streaming;

    public McpTool(ObjectNode descriptor, Handler handler) {
        this(descriptor, handler, null);
    }

    public McpTool(ObjectNode descriptor, Handler handler, ObjectNode requiredClientCapabilities) {
        this(descriptor, handler, requiredClientCapabilities, false);
    }

    public McpTool(ObjectNode descriptor, Handler handler, ObjectNode requiredClientCapabilities, boolean streaming) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor").deepCopy();
        this.handler = Objects.requireNonNull(handler, "handler");
        this.requiredClientCapabilities = requiredClientCapabilities == null
                ? null
                : requiredClientCapabilities.deepCopy();
        this.streaming = streaming;
    }

    public String name() {
        return descriptor.path("name").asText();
    }

    public ObjectNode descriptor() {
        return descriptor.deepCopy();
    }

    public Handler handler() {
        return handler;
    }

    public ObjectNode requiredClientCapabilities() {
        return requiredClientCapabilities == null ? null : requiredClientCapabilities.deepCopy();
    }

    public boolean streaming() {
        return streaming;
    }

    @FunctionalInterface
    public interface Handler {
        ToolResult execute(ObjectNode arguments, McpContext context) throws Exception;
    }
}
