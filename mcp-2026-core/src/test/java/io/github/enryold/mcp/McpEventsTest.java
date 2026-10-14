package io.github.enryold.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class McpEventsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void acknowledgesFirstFiltersByIdentityAndReauthorizesDelivery() throws Exception {
        var revoked = new AtomicBoolean();
        McpAuthorization authorization = new McpAuthorization() {
            public void authorize(McpContext context, String method, String name) {
                if (revoked.get()) throw new McpAccessException("revoked");
            }
            public boolean visible(McpContext context, String name) { return true; }
        };
        var emitted = new CopyOnWriteArrayList<ObjectNode>();
        ObjectNode notifications = JSON.createObjectNode().put("toolsListChanged", true);
        ObjectNode params = JSON.createObjectNode().set("notifications", notifications);
        params.set("_meta", metadata());
        McpRequest request = new McpRequest(JSON.valueToTree("sub-1"), "subscriptions/listen", params, params.withObject("_meta"));
        McpContext subscriber = context("alice", "red").withAuthorization(authorization).withRequest(request, emitted::add);
        McpEvents events = new McpEvents(Set.of(McpEvents.Kind.TOOLS_LIST_CHANGED), 1);
        McpMethod listen = events.methods().iterator().next();
        assertThat(events.capabilities().path("tools").path("listChanged").asBoolean()).isTrue();
        assertThat(events.capabilities().has("resources")).isFalse();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> listen.handler().execute(params, subscriber));
            awaitSize(emitted, 1);
            events.toolsChanged(context("alice", "blue"));
            assertThat(emitted).hasSize(1);
            events.toolsChanged(context("alice", "red"));
            awaitSize(emitted, 2);
            revoked.set(true);
            events.toolsChanged(context("alice", "red"));

            ObjectNode completed = result.get(1, TimeUnit.SECONDS);
            assertThat(emitted).extracting(value -> value.path("method").asString()).containsExactly(
                    "notifications/subscriptions/acknowledged", "notifications/tools/list_changed");
            assertThat(emitted).allSatisfy(value -> assertThat(value.path("params").path("_meta")
                    .path("io.modelcontextprotocol/subscriptionId").asString()).isEqualTo("sub-1"));
            assertThat(completed.path("_meta").path("io.modelcontextprotocol/subscriptionId").asString())
                    .isEqualTo("sub-1");
        } finally {
            events.close();
        }
    }

    @Test void emptyFilterStaysOpenUntilDisconnectAndReleasesItsSlot() throws Exception {
        var disconnected = new AtomicBoolean();
        var emitted = new CopyOnWriteArrayList<ObjectNode>();
        ObjectNode params = JSON.createObjectNode().set("notifications", JSON.createObjectNode());
        params.set("_meta", metadata());
        McpRequest request = new McpRequest(JSON.valueToTree("empty-sub"), "subscriptions/listen",
                params, params.withObject("_meta"));
        McpContext subscriber = new McpContext("alice", "red", Set.of("read"), Map.of(),
                Instant.now().plusSeconds(5), disconnected::get).withRequest(request, emitted::add);
        McpEvents events = new McpEvents(Set.of(McpEvents.Kind.TOOLS_LIST_CHANGED), 1);
        McpMethod listen = events.methods().iterator().next();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> listen.handler().execute(params, subscriber));
            awaitSize(emitted, 1);
            assertThat(result).isNotDone();
            assertThat(events.subscriptionCount()).isEqualTo(1);
            assertThat(emitted.getFirst().path("params").path("notifications").isEmpty()).isTrue();

            disconnected.set(true);

            assertThat(result.get(1, TimeUnit.SECONDS).path("_meta")
                    .path("io.modelcontextprotocol/subscriptionId").asString()).isEqualTo("empty-sub");
            assertThat(events.subscriptionCount()).isZero();
        } finally {
            events.close();
        }
    }

    @Test void resourceEventsHonorListAndExactUriFiltersWithWireData() throws Exception {
        var cancelled = new AtomicBoolean();
        var emitted = new CopyOnWriteArrayList<ObjectNode>();
        ObjectNode notifications = JSON.createObjectNode().put("resourcesListChanged", true);
        notifications.putArray("resourceSubscriptions").add("test://static-text");
        ObjectNode params = JSON.createObjectNode().set("notifications", notifications);
        params.set("_meta", metadata());
        McpRequest request = new McpRequest(JSON.valueToTree("resource-sub"), "subscriptions/listen",
                params, params.withObject("_meta"));
        McpContext subscriber = new McpContext("alice", "red", Set.of("read"), Map.of(),
                Instant.now().plusSeconds(5), cancelled::get).withRequest(request, emitted::add);
        McpEvents events = new McpEvents(Set.of(
                McpEvents.Kind.RESOURCES_LIST_CHANGED,
                McpEvents.Kind.RESOURCE_UPDATED), 1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> events.methods().iterator().next().handler().execute(params, subscriber));
            awaitSize(emitted, 1);
            assertThat(events.capabilities().path("resources").path("listChanged").asBoolean()).isTrue();
            assertThat(events.capabilities().path("resources").path("subscribe").asBoolean()).isTrue();

            events.resourceUpdated(context("alice", "red"), "test://static-binary");
            assertThat(emitted).hasSize(1);
            events.resourcesChanged(context("alice", "red"));
            events.resourceUpdated(context("alice", "red"), "test://static-text");
            awaitSize(emitted, 3);
            cancelled.set(true);
            result.get(1, TimeUnit.SECONDS);

            assertThat(emitted).extracting(value -> value.path("method").asString()).containsExactly(
                    "notifications/subscriptions/acknowledged",
                    "notifications/resources/list_changed",
                    "notifications/resources/updated");
            assertThat(emitted.get(1).path("params").has("uri")).isFalse();
            assertThat(emitted.get(2).path("params").path("uri").asString()).isEqualTo("test://static-text");
            assertThat(emitted.subList(1, 3)).allSatisfy(value -> assertThat(value.path("params").path("_meta")
                    .path("io.modelcontextprotocol/subscriptionId").asString()).isEqualTo("resource-sub"));
        } finally {
            events.close();
        }
    }

    private static void awaitSize(List<?> values, int size) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (values.size() < size && System.nanoTime() < end) Thread.sleep(10);
        assertThat(values).hasSize(size);
    }

    private static McpContext context(String subject, String tenant) {
        return new McpContext(subject, tenant, Set.of("read"), Map.of(), Instant.now().plusSeconds(5), () -> false);
    }

    private static ObjectNode metadata() {
        return JSON.createObjectNode()
                .put("io.modelcontextprotocol/protocolVersion", McpEngine.PROTOCOL_VERSION)
                .set("io.modelcontextprotocol/clientCapabilities", JSON.createObjectNode());
    }
}
