package io.github.enryold.mcp;

import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/** Explicit prompt registration. */
public final class McpPrompt {
    private final ObjectNode descriptor;
    private final Handler handler;

    public McpPrompt(ObjectNode descriptor, Handler handler) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor").deepCopy();
        this.handler = Objects.requireNonNull(handler, "handler");
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

    @FunctionalInterface
    public interface Handler {
        ObjectNode get(ObjectNode arguments, McpContext context) throws Exception;
    }
}
