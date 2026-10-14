package io.github.enryold.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpNotificationsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void emitsRequestedProgressMonotonicallyAndFiltersLogs() {
        var emitted = new ArrayList<ObjectNode>();
        ObjectNode metadata = metadata().put("progressToken", "work-1")
                .put("io.modelcontextprotocol/logLevel", "info");
        McpContext context = context("alice", "red", metadata, emitted);
        McpNotifications notifications = McpNotifications.forRequest(context);

        notifications.progress(0, 100d, "starting");
        notifications.progress(50, 100d, null);
        notifications.log("debug", null, JSON.valueToTree("hidden"));
        notifications.log("error", "fixture", JSON.valueToTree("safe"));

        assertThat(emitted).extracting(value -> value.path("method").asString())
                .containsExactly("notifications/progress", "notifications/progress", "notifications/message");
        assertThat(emitted.getFirst().path("params").path("progressToken").asString()).isEqualTo("work-1");
        assertThat(emitted.getLast().path("params").path("level").asString()).isEqualTo("error");
        assertThatThrownBy(() -> notifications.progress(50, 100d, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void neverLogsWithoutRequestOptInAndRejectsProgressWithoutToken() {
        var emitted = new ArrayList<ObjectNode>();
        McpNotifications notifications = McpNotifications.forRequest(context("alice", "red", metadata(), emitted));

        notifications.log("emergency", null, JSON.valueToTree("hidden"));

        assertThat(emitted).isEmpty();
        assertThatThrownBy(() -> notifications.progress(1, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void everyHelperAndContextCopySharesProgressOrderingForTheActiveRequest() {
        var emitted = new ArrayList<ObjectNode>();
        var context = context("alice", "red", metadata().put("progressToken", "shared"), emitted);
        McpNotifications.forRequest(context).progress(2, null, null);
        var copy = context.withAuthorization(McpAuthorization.permitAll()).withLifecycle(context.deadline(), () -> false);
        assertThatThrownBy(() -> McpNotifications.forRequest(copy).progress(1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(emitted).hasSize(1);
        McpNotifications.forRequest(copy).progress(3, null, null);
        assertThat(emitted).hasSize(2);
        var parameters = copy.requestParams().put("requestState", "handler-view");
        var transformed = copy.withRequestPreservingProgress(new McpRequest(copy.requestId(), "tools/call", parameters, copy.requestMetadata()));
        assertThatThrownBy(() -> McpNotifications.forRequest(transformed).progress(2, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(emitted).hasSize(2);
    }

    private static McpContext context(String subject, String tenant, ObjectNode metadata, ArrayList<ObjectNode> emitted) {
        ObjectNode params = JSON.createObjectNode().set("_meta", metadata);
        McpRequest request = new McpRequest(JSON.valueToTree("request-1"), "tools/call", params, metadata);
        return new McpContext(subject, tenant, Set.of("read"), Map.of(), Instant.now().plusSeconds(5), () -> false)
                .withRequest(request, emitted::add);
    }

    private static ObjectNode metadata() {
        return JSON.createObjectNode()
                .put("io.modelcontextprotocol/protocolVersion", McpEngine.PROTOCOL_VERSION)
                .set("io.modelcontextprotocol/clientCapabilities", JSON.createObjectNode());
    }
}
