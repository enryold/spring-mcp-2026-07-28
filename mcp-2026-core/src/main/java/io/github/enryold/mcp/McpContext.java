package io.github.enryold.mcp;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class McpContext {
    private final String subject;
    private final String tenant;
    private final Set<String> scopes;
    private final Map<String, Object> attributes;
    private final Instant deadline;
    private final BooleanSupplier cancellation;
    private final McpRequest request;
    private final Consumer<ObjectNode> notifications;
    private final McpAuthorization authorization;
    private final ProgressState progressState;

    public McpContext(
            String subject,
            String tenant,
            Set<String> scopes,
            Map<String, Object> attributes,
            Instant deadline,
            BooleanSupplier cancellation) {
        this(subject, tenant, scopes, attributes, deadline, cancellation, null, notification -> {}, McpAuthorization.permitAll(), new ProgressState());
    }

    private McpContext(String subject, String tenant, Set<String> scopes, Map<String, Object> attributes,
            Instant deadline, BooleanSupplier cancellation, McpRequest request, Consumer<ObjectNode> notifications,
            McpAuthorization authorization, ProgressState progressState) {
        this.subject = requireText(subject, "subject");
        this.tenant = requireText(tenant, "tenant");
        this.scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        this.attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.request = request;
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.progressState = progressState;
    }

    public McpContext withRequest(McpRequest request) {
        return withRequest(request, notifications);
    }

    public McpContext withRequest(McpRequest request, Consumer<ObjectNode> notifications) {
        return new McpContext(subject, tenant, scopes, attributes, deadline, cancellation,
                Objects.requireNonNull(request, "request"), notifications, authorization,
                request.equals(this.request) ? progressState : new ProgressState());
    }

    public McpContext withAuthorization(McpAuthorization authorization) {
        return new McpContext(subject, tenant, scopes, attributes, deadline, cancellation, request, notifications, authorization, progressState);
    }

    public McpContext withLifecycle(Instant deadline, BooleanSupplier cancellation) {
        return new McpContext(subject, tenant, scopes, attributes, deadline, cancellation, request, notifications, authorization, progressState);
    }

    McpContext bufferingNotifications(Consumer<ObjectNode> sink) {
        return new McpContext(subject, tenant, scopes, attributes, deadline, cancellation, request,
                sink, authorization, new ProgressState());
    }

    McpContext withRequestPreservingProgress(McpRequest request) {
        return new McpContext(subject, tenant, scopes, attributes, deadline, cancellation,
                Objects.requireNonNull(request, "request"), notifications, authorization, progressState);
    }

    /** Background work has its own lifetime and no original HTTP response stream. */
    public McpContext withDetachedLifecycle(Instant deadline, BooleanSupplier cancellation) {
        return new McpContext(subject, tenant, scopes, attributes, deadline, cancellation, request, notification -> {}, authorization, new ProgressState());
    }

    public void authorize(String method, String name) {
        authorization.authorize(this, method, name);
    }

    public boolean visible(String name) {
        return authorization.visible(this, name);
    }

    public boolean visible(String method, String name) {
        return authorization.visible(this, method, name);
    }

    public ObjectNode requestParams() {
        return request == null ? JsonMapper.builder().build().createObjectNode() : request.params();
    }

    public ObjectNode requestMetadata() {
        return request == null ? JsonMapper.builder().build().createObjectNode() : request.metadata();
    }

    public JsonNode requestId() {
        return request == null ? null : request.id();
    }

    public void emitNotification(ObjectNode notification) {
        Objects.requireNonNull(notification, "notification");
        if (!"2.0".equals(notification.path("jsonrpc").asString()) || notification.has("id")
                || !notification.path("method").isString()
                || !notification.path("method").asString().startsWith("notifications/")
                || notification.has("result") || notification.has("error")) {
            throw new IllegalArgumentException("response streams accept only JSON-RPC notifications");
        }
        if ("notifications/progress".equals(notification.path("method").asString())) {
            synchronized (progressState) {
                JsonNode params = notification.path("params");
                JsonNode value = params.path("progress");
                JsonNode token = requestMetadata().get("progressToken");
                double progress = value.isNumber() ? value.asDouble() : Double.NaN;
                if (token == null || !token.equals(params.get("progressToken"))
                        || !Double.isFinite(progress) || progress <= progressState.last) {
                    throw new IllegalArgumentException("progress must match the active token and strictly increase");
                }
                if (!isCancelled()) {
                    progressState.last = progress;
                    notifications.accept(notification.deepCopy());
                }
            }
        } else if (!isCancelled()) notifications.accept(notification.deepCopy());
    }

    public String subject() {
        return subject;
    }

    public String tenant() {
        return tenant;
    }

    public Set<String> scopes() {
        return scopes;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public Instant deadline() {
        return deadline;
    }

    public boolean isCancelled() {
        return cancellation.getAsBoolean() || !Instant.now().isBefore(deadline);
    }

    @Override
    public String toString() {
        return "McpContext[subject=" + subject + ", tenant=" + tenant + ", scopes=" + scopes + ", deadline=" + deadline + ']';
    }

    private static final class ProgressState {
        private double last = Double.NEGATIVE_INFINITY;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
