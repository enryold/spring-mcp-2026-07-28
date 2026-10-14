package io.github.enryold.mcp;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public record McpRequest(JsonNode id, String method, ObjectNode params, ObjectNode metadata) {
    public McpRequest {
        id = Objects.requireNonNull(id, "id").deepCopy();
        method = Objects.requireNonNull(method, "method");
        params = Objects.requireNonNull(params, "params").deepCopy();
        metadata = Objects.requireNonNull(metadata, "metadata").deepCopy();
    }

    @Override
    public JsonNode id() {
        return id.deepCopy();
    }

    @Override
    public ObjectNode params() {
        return params.deepCopy();
    }

    @Override
    public ObjectNode metadata() {
        return metadata.deepCopy();
    }
}
