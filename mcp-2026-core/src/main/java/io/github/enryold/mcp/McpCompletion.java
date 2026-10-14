package io.github.enryold.mcp;

import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/** Explicit completion registration for one prompt or resource-template argument. */
public final class McpCompletion {
    private final ObjectNode reference;
    private final String argumentName;
    private final Handler handler;

    public McpCompletion(ObjectNode reference, String argumentName, Handler handler) {
        this.reference = Objects.requireNonNull(reference, "reference").deepCopy();
        if (argumentName == null || argumentName.isBlank()) {
            throw new IllegalArgumentException("completion argumentName is required");
        }
        this.argumentName = argumentName;
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public ObjectNode reference() {
        return reference.deepCopy();
    }

    public String argumentName() {
        return argumentName;
    }

    public Handler handler() {
        return handler;
    }

    @FunctionalInterface
    public interface Handler {
        ObjectNode complete(String value, ObjectNode previousArguments, McpContext context) throws Exception;
    }
}
