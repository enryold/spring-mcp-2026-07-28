package io.github.enryold.mcp;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class McpProtocolException extends RuntimeException {
    private final int httpStatus;
    private final int code;
    private final JsonNode data;
    private final JsonNode id;

    public McpProtocolException(int httpStatus, int code, String message, JsonNode data, JsonNode id) {
        super(Objects.requireNonNull(message, "message"));
        if (id != null && !validId(id)) {
            throw new IllegalArgumentException("id must be absent, a string, or an integer");
        }
        this.httpStatus = httpStatus;
        this.code = code;
        this.data = data == null ? null : data.deepCopy();
        this.id = id == null ? null : id.deepCopy();
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int code() {
        return code;
    }

    public JsonNode data() {
        return data == null ? null : data.deepCopy();
    }

    public JsonNode id() {
        return id == null ? null : id.deepCopy();
    }

    public ObjectNode toResponse() {
        ObjectNode response = JsonNodeFactory.instance.objectNode().put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id.deepCopy());
        }
        ObjectNode error = response.putObject("error")
                .put("code", code)
                .put("message", getMessage());
        if (data != null) {
            error.set("data", data.deepCopy());
        }
        return response;
    }

    private static boolean validId(JsonNode id) {
        if (id.isTextual() || id.isIntegralNumber()) {
            return true;
        }
        if (!id.isNumber() || id.isFloatingPointNumber() && !Double.isFinite(id.asDouble())) {
            return false;
        }
        try {
            return id.decimalValue().stripTrailingZeros().scale() <= 0;
        } catch (ArithmeticException error) {
            return false;
        }
    }
}
