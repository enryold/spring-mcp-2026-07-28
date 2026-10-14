package io.github.enryold.mcp;

import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/** Explicit server method registration; schema names refer to the pinned protocol definitions. */
public record McpMethod(String name, String requestSchema, String resultSchema, Handler handler) {
    public McpMethod {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("method name is required");
        Objects.requireNonNull(handler, "handler");
    }

    @FunctionalInterface
    public interface Handler {
        ObjectNode execute(ObjectNode params, McpContext context) throws Exception;
    }
}
