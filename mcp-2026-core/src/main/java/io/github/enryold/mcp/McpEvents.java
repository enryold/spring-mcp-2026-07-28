package io.github.enryold.mcp;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Bounded, identity-scoped change subscriptions for a single server process. */
public final class McpEvents implements McpModule, AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId";
    private final Set<Kind> supported;
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final Semaphore slots;
    private final AtomicBoolean closed = new AtomicBoolean();

    public McpEvents(Set<Kind> supported, int maxSubscriptions) {
        this.supported = Set.copyOf(Objects.requireNonNull(supported, "supported"));
        if (maxSubscriptions < 1) throw new IllegalArgumentException("maxSubscriptions must be positive");
        this.slots = new Semaphore(maxSubscriptions);
    }

    @Override
    public ObjectNode capabilities() {
        ObjectNode capabilities = JSON.createObjectNode();
        if (supported.contains(Kind.TOOLS_LIST_CHANGED)) capabilities.putObject("tools").put("listChanged", true);
        if (supported.contains(Kind.PROMPTS_LIST_CHANGED)) capabilities.putObject("prompts").put("listChanged", true);
        if (supported.contains(Kind.RESOURCES_LIST_CHANGED) || supported.contains(Kind.RESOURCE_UPDATED)) {
            ObjectNode resources = capabilities.putObject("resources");
            if (supported.contains(Kind.RESOURCES_LIST_CHANGED)) resources.put("listChanged", true);
            if (supported.contains(Kind.RESOURCE_UPDATED)) resources.put("subscribe", true);
        }
        return capabilities;
    }

    @Override
    public Collection<McpMethod> methods() {
        return java.util.List.of(new McpMethod(
                "subscriptions/listen", "SubscriptionsListenRequest", "SubscriptionsListenResult", this::listen));
    }

    public void toolsChanged(McpContext audience) {
        publish(audience, Kind.TOOLS_LIST_CHANGED, null);
    }

    public void promptsChanged(McpContext audience) {
        publish(audience, Kind.PROMPTS_LIST_CHANGED, null);
    }

    public void resourcesChanged(McpContext audience) {
        publish(audience, Kind.RESOURCES_LIST_CHANGED, null);
    }

    public void resourceUpdated(McpContext audience, String uri) {
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("resource URI is required");
        publish(audience, Kind.RESOURCE_UPDATED, uri);
    }

    int subscriptionCount() {
        return subscriptions.size();
    }

    private ObjectNode listen(ObjectNode params, McpContext context) throws InterruptedException {
        ObjectNode requested = params.get("notifications") instanceof ObjectNode filter ? filter : null;
        if (requested == null) throw invalid(context, "notifications is required");
        if (!slots.tryAcquire()) throw internal(context, "Subscription limit reached");
        Subscription subscription = new Subscription(context, honored(requested));
        try {
            if (closed.get()) throw internal(context, "Subscription service is closed");
            subscriptions.add(subscription);
            if (closed.get()) throw internal(context, "Subscription service is closed");
            synchronized (subscription) {
                context.emitNotification(acknowledged(context.requestId(), subscription.filter));
                subscription.ready = true;
            }
            while (!closed.get() && !subscription.closed.get() && !context.isCancelled()) {
                subscription.done.await(100, TimeUnit.MILLISECONDS);
            }
            return completed(context.requestId());
        } finally {
            subscriptions.remove(subscription);
            slots.release();
        }
    }

    private ObjectNode honored(ObjectNode requested) {
        ObjectNode honored = JSON.createObjectNode();
        for (Kind kind : supported) {
            JsonNode value = requested.get(kind.filterField);
            if (kind == Kind.RESOURCE_UPDATED) {
                if (value != null && value.isArray() && !value.isEmpty()) honored.set(kind.filterField, value.deepCopy());
            } else if (value != null && value.isBoolean() && value.booleanValue()) {
                honored.put(kind.filterField, true);
            }
        }
        return honored;
    }

    private void publish(McpContext audience, Kind kind, String uri) {
        Objects.requireNonNull(audience, "audience");
        if (closed.get() || !supported.contains(kind)) return;
        for (Subscription subscription : subscriptions) {
            if (!sameIdentity(audience, subscription.context) || !accepts(subscription.filter, kind, uri)) continue;
            try {
                synchronized (subscription) {
                    if (!subscription.ready || subscription.closed.get()) continue;
                    subscription.context.authorize(kind.authorizationMethod, uri);
                    subscription.context.emitNotification(change(kind, uri, subscription.context.requestId()));
                }
            } catch (RuntimeException rejectedOrFull) {
                subscription.close();
            }
        }
    }

    private static boolean sameIdentity(McpContext left, McpContext right) {
        return left.subject().equals(right.subject()) && left.tenant().equals(right.tenant());
    }

    private static boolean accepts(ObjectNode filter, Kind kind, String uri) {
        JsonNode value = filter.get(kind.filterField);
        if (kind != Kind.RESOURCE_UPDATED) return value != null && value.booleanValue();
        if (value == null || !value.isArray()) return false;
        for (JsonNode candidate : value) if (candidate.isTextual() && candidate.asString().equals(uri)) return true;
        return false;
    }

    private static ObjectNode acknowledged(JsonNode id, ObjectNode filter) {
        ObjectNode params = JSON.createObjectNode();
        params.putObject("_meta").set(SUBSCRIPTION_ID, id.deepCopy());
        params.set("notifications", filter.deepCopy());
        return notification("notifications/subscriptions/acknowledged", params);
    }

    private static ObjectNode change(Kind kind, String uri, JsonNode id) {
        ObjectNode params = JSON.createObjectNode();
        params.putObject("_meta").set(SUBSCRIPTION_ID, id.deepCopy());
        if (uri != null) params.put("uri", uri);
        return notification(kind.notificationMethod, params);
    }

    private static ObjectNode notification(String method, ObjectNode params) {
        return JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method).set("params", params);
    }

    private static ObjectNode completed(JsonNode id) {
        ObjectNode result = JSON.createObjectNode();
        result.putObject("_meta").set(SUBSCRIPTION_ID, id.deepCopy());
        return result;
    }

    private static McpProtocolException invalid(McpContext context, String message) {
        return new McpProtocolException(200, -32602, message, null, context.requestId());
    }

    private static McpProtocolException internal(McpContext context, String message) {
        return new McpProtocolException(200, -32603, message, null, context.requestId());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        subscriptions.forEach(Subscription::close);
    }

    public enum Kind {
        TOOLS_LIST_CHANGED("toolsListChanged", "notifications/tools/list_changed", "tools/list"),
        PROMPTS_LIST_CHANGED("promptsListChanged", "notifications/prompts/list_changed", "prompts/list"),
        RESOURCES_LIST_CHANGED("resourcesListChanged", "notifications/resources/list_changed", "resources/list"),
        RESOURCE_UPDATED("resourceSubscriptions", "notifications/resources/updated", "resources/read");

        private final String filterField;
        private final String notificationMethod;
        private final String authorizationMethod;

        Kind(String filterField, String notificationMethod, String authorizationMethod) {
            this.filterField = filterField;
            this.notificationMethod = notificationMethod;
            this.authorizationMethod = authorizationMethod;
        }
    }

    private static final class Subscription {
        private final McpContext context;
        private final ObjectNode filter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch done = new CountDownLatch(1);
        private boolean ready;

        private Subscription(McpContext context, ObjectNode filter) {
            this.context = context;
            this.filter = filter;
        }

        private void close() {
            closed.set(true);
            done.countDown();
        }
    }
}
