package io.github.enryold.mcp;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Request-scoped progress and opt-in protocol logging notifications. */
public final class McpNotifications {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> LEVELS = List.of(
            "emergency", "alert", "critical", "error", "warning", "notice", "info", "debug");
    private final McpContext context;
    private final JsonNode progressToken;
    private final int logLevel;

    private McpNotifications(McpContext context) {
        this.context = Objects.requireNonNull(context, "context");
        JsonNode metadata = context.requestMetadata();
        JsonNode token = metadata.get("progressToken");
        this.progressToken = token == null ? null : token.deepCopy();
        String requested = metadata.path("io.modelcontextprotocol/logLevel").asString(null);
        this.logLevel = requested == null ? -1 : LEVELS.indexOf(requested);
        if (requested != null && logLevel < 0) throw new IllegalArgumentException("invalid MCP log level");
    }

    public static McpNotifications forRequest(McpContext context) {
        return new McpNotifications(context);
    }

    public synchronized void progress(double progress, Double total, String message) {
        if (progressToken == null || !(progressToken.isTextual() || progressToken.isIntegralNumber())) {
            throw new IllegalStateException("request did not provide a progress token");
        }
        if (!Double.isFinite(progress)
                || total != null && (!Double.isFinite(total) || total < 0)) {
            throw new IllegalArgumentException("progress must be finite and strictly increasing; total must be non-negative");
        }
        ObjectNode params = JSON.createObjectNode().set("progressToken", progressToken.deepCopy());
        params.put("progress", progress);
        if (total != null) params.put("total", total);
        if (message != null) params.put("message", message);
        context.emitNotification(notification("notifications/progress", params));
    }

    public void log(String level, String logger, JsonNode data) {
        int severity = LEVELS.indexOf(level);
        if (severity < 0) throw new IllegalArgumentException("invalid MCP log level");
        if (logLevel < 0 || severity > logLevel) return;
        ObjectNode params = JSON.createObjectNode().put("level", level);
        if (logger != null) params.put("logger", logger);
        params.set("data", Objects.requireNonNull(data, "data").deepCopy());
        context.emitNotification(notification("notifications/message", params));
    }

    private static ObjectNode notification(String method, ObjectNode params) {
        return JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method).set("params", params);
    }
}
