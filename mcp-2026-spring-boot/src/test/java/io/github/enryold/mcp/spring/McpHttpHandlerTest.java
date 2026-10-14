package io.github.enryold.mcp.spring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import io.github.enryold.mcp.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpHttpHandlerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VERSION = "2026-07-28";
    private final McpSecurity security = new McpSecurity() {
        public McpContext authenticate(HttpServletRequest request, Instant deadline, BooleanSupplier cancelled) {
            if (!"Bearer test".equals(request.getHeader("Authorization")))
                throw new McpProtocolException(401, -32001, "Authentication required", null, null);
            return new McpContext("test", "tenant", Set.of("read"), Map.of(), deadline, cancelled);
        }
        public void authorize(McpContext context, String method, String name) {}
        public boolean visible(McpContext context, String name) { return true; }
    };

    @Test void validatesMirroredHeadersAndBase64Unicode() throws Exception {
        try (var handler = handler(defaults(), echo())) {
            var missing = request("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}", 1);
            assertError(send(handler, missing), 400, -32020);
            var valid = request("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"Caffè ☕\"}}", "one");
            valid.addHeader("Mcp-Param-Text", "=?base64?" + Base64.getEncoder()
                    .encodeToString("Caffè ☕".getBytes(StandardCharsets.UTF_8)) + "?=");
            var response = send(handler, valid);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(JSON.readTree(response.getContentAsByteArray()).path("id").asString()).isEqualTo("one");
            assertThat(JSON.readTree(response.getContentAsByteArray()).path("result").path("content").get(0)
                    .path("text").asString()).isEqualTo("Caffè ☕");
            valid.removeHeader("MCP-Protocol-Version");
            assertError(send(handler, valid), 400, -32020);
        }
    }

    @Test void transportGatesAndPrivateCatalog() throws Exception {
        try (var handler = handler(defaults(), echo())) {
            var discover = request("server/discover", "{}", 1);
            var response = send(handler, discover);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Cache-Control")).contains("private", "no-store");
            assertThat(response.getHeader("Mcp-Session-Id")).isNull();
            discover.removeHeader("Authorization");
            var unauthorized = send(handler, discover);
            assertError(unauthorized, 401, -32001);
            assertThat(unauthorized.getHeader("WWW-Authenticate")).startsWith("Bearer");
            discover.addHeader("Authorization", "Bearer test");
            discover.addHeader("Origin", "https://attacker.example");
            assertError(send(handler, discover), 403, -32000);
            discover.removeHeader("Origin");
            discover.setContentType("text/plain");
            assertError(send(handler, discover), 415, -32000);
            discover.setContentType("application/json");
            discover.removeHeader("Accept");
            assertError(send(handler, discover), 406, -32000);
            discover.addHeader("Accept", "application/json;q=0, text/event-stream");
            assertError(send(handler, discover), 406, -32000);
            for (String method : List.of("GET", "DELETE", "PUT")) {
                discover.setMethod(method);
                var wrongMethod = send(handler, discover);
                assertThat(wrongMethod.getStatus()).isEqualTo(405);
                assertThat(wrongMethod.getHeader("Allow")).isEqualTo("POST");
            }
        }
    }

    @Test void bodyLimitPreventsDispatch() throws Exception {
        var invoked = new AtomicInteger();
        var tool = new McpTool(descriptor(), (args, ctx) -> {
            invoked.incrementAndGet();
            return ToolResult.success(JSON.createArrayNode());
        });
        var limits = new McpProperties(true, "/mcp", "test", "1", 16, 64, Duration.ofSeconds(1), 1, List.of());
        try (var handler = handler(limits, tool)) {
            assertError(send(handler, request("server/discover", "{}", 1)), 413, -32000);
            assertThat(invoked).hasValue(0);
        }
    }

    @Test void streamsNotificationsBeforeTheTerminalResponse() throws Exception {
        var tool = new McpTool(descriptor(), (args, context) -> {
            var notifications = McpNotifications.forRequest(context);
            notifications.progress(1, 2d, "half");
            notifications.progress(2, 2d, "done");
            return ToolResult.success(JSON.createArrayNode());
        }, null, true);
        try (var handler = handler(defaults(), tool)) {
            var request = request("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}", "stream-1");
            request.addHeader("Mcp-Param-Text", "hello");
            ObjectNode body = (ObjectNode) JSON.readTree(request.getContentAsByteArray());
            body.withObject("params").withObject("_meta").put("progressToken", "progress-1");
            request.setContent(JSON.writeValueAsBytes(body));

            var response = send(handler, request);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).startsWith("text/event-stream");
            assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
            String stream = response.getContentAsString();
            int first = stream.indexOf("\"progress\":1.0");
            int second = stream.indexOf("\"progress\":2.0");
            int terminal = stream.indexOf("\"id\":\"stream-1\"");
            assertThat(first).isGreaterThanOrEqualTo(0);
            assertThat(second).isGreaterThan(first);
            assertThat(terminal).isGreaterThan(second);
            assertThat(stream).doesNotContain("event: request");
        }
    }

    @Test void deadlineAndSaturationNeverRetryOrFreeCapacityForStillRunningMutation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var invoked = new AtomicInteger();
        var tool = new McpTool(descriptor(), (args, ctx) -> {
            invoked.incrementAndGet();
            entered.countDown();
            try {
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { /* simulate committed external work */ }
                }
                return ToolResult.success(JSON.createArrayNode());
            } finally { finished.countDown(); }
        });
        var limits = new McpProperties(true, "/mcp", "test", "1", 1048576, 64,
                Duration.ofMillis(100), 1, List.of());
        try (var handler = handler(limits, tool)) {
            var call = request("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}", 1);
            call.addHeader("Mcp-Param-Text", "hello");
            var timeout = send(handler, call);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertError(timeout, 408, -32000);
            var saturated = send(handler, request("server/discover", "{}", 2));
            assertError(saturated, 429, -32000);
            assertThat(saturated.getHeader("Retry-After")).isEqualTo("1");
            assertThat(invoked).hasValue(1);
            release.countDown();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
    }

    @Test void customEngineCannotBypassHostAuthorizationAndCatalogVisibility() throws Exception {
        var deny = new McpSecurity() {
            public McpContext authenticate(HttpServletRequest request, Instant deadline, BooleanSupplier cancelled) {
                return security.authenticate(request, deadline, cancelled);
            }
            public void authorize(McpContext context, String method, String name) {
                if (method.equals("tools/call")) throw new McpAccessException("Forbidden");
            }
            public boolean visible(McpContext context, String name) { return false; }
        };
        var permissive = new McpEngine(JSON.readTree("{\"name\":\"test\",\"version\":\"1\"}"),
                List.of(echo()), McpAuthorization.permitAll());
        try (var handler = new McpHttpHandler(permissive, defaults(), deny)) {
            var list = send(handler, request("tools/list", "{}", 1));
            assertThat(JSON.readTree(list.getContentAsByteArray()).path("result").path("tools")).isEmpty();
            var call = request("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}", 2);
            call.addHeader("Mcp-Param-Text", "hello");
            var rejected = send(handler, call);
            assertError(rejected, 403, -32001);
            assertThat(rejected.getHeader("WWW-Authenticate")).contains("insufficient_scope");
        }
    }

    @Test void streamingEligibilityDoesNotHideAuthorizationStatus() throws Exception {
        var deny = new McpSecurity() {
            public McpContext authenticate(HttpServletRequest request, Instant deadline, BooleanSupplier cancelled) {
                return security.authenticate(request, deadline, cancelled);
            }
            public void authorize(McpContext context, String method, String name) {
                throw new McpAccessException("Forbidden");
            }
            public boolean visible(McpContext context, String name) { return false; }
        };
        var streaming = new McpTool(descriptor(), (args, context) -> ToolResult.success(JSON.createArrayNode()),
                null, true);
        try (var handler = new McpHttpHandler(new McpEngine(JSON.readTree("{\"name\":\"test\",\"version\":\"1\"}"),
                List.of(streaming), McpAuthorization.permitAll()), defaults(), deny)) {
            var call = request("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}", 1);
            call.addHeader("Mcp-Param-Text", "hello");

            var response = send(handler, call);

            assertError(response, 403, -32001);
            assertThat(response.getContentType()).startsWith("application/json");
        }
    }

    @Test void validatesRegisteredTaskRoutingNamesAgainstTaskIds() throws Exception {
        try (var handler = handler(defaults(), taskRoutingModule())) {
            for (String method : List.of("tasks/get", "tasks/update", "tasks/cancel")) {
                var matching = request(method, "{\"taskId\":\"task-123\"}", method + "-matching");
                matching.addHeader("Mcp-Name", "task-123");
                assertThat(send(handler, matching).getStatus()).isEqualTo(200);

                var mismatched = request(method, "{\"taskId\":\"task-123\"}", method + "-mismatched");
                mismatched.addHeader("Mcp-Name", "another-task");
                assertError(send(handler, mismatched), 400, -32020);

                var missing = request(method, "{\"taskId\":\"task-123\"}", method + "-missing");
                assertError(send(handler, missing), 400, -32020);
            }
        }
    }

    @Test void unsupportedTaskMethodStillUsesMethodNotFoundRouting() throws Exception {
        try (var handler = handler(defaults(), echo())) {
            assertError(send(handler, request("tasks/get", "{\"taskId\":\"unknown\"}", 1)), 404, -32601);
        }
    }

    @Test void sseDisconnectCancelsEmptySubscriptionAndReleasesItsBoundedSlot() throws Exception {
        McpEvents events = new McpEvents(Set.of(McpEvents.Kind.TOOLS_LIST_CHANGED), 1);
        var limits = new McpProperties(true, "/mcp", "test", "1", 1048576, 64,
                Duration.ofMillis(100), 2, List.of());
        try (events; var handler = handler(limits, events)) {
            var disconnected = request("subscriptions/listen", "{\"notifications\":{}}", "disconnected");
            assertThatThrownBy(() -> handler.handleRequest(disconnected, new DisconnectingResponse()))
                    .isInstanceOf(IOException.class);

            MockHttpServletResponse replacement = null;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < end) {
                replacement = send(handler, request(
                        "subscriptions/listen", "{\"notifications\":{}}", "replacement"));
                if (replacement.getContentType() != null
                        && replacement.getContentType().startsWith("text/event-stream")) break;
                Thread.sleep(10);
            }

            assertThat(replacement).isNotNull();
            assertThat(replacement.getContentType()).startsWith("text/event-stream");
            assertThat(replacement.getContentAsString())
                    .contains("notifications/subscriptions/acknowledged", "\"id\":\"replacement\"");
        }
    }

    @Test void hostRateLimitPreservesRetryAfterAndDoesNotExecute() throws Exception {
        var invoked = new AtomicInteger();
        var tool = new McpTool(descriptor(), (args, ctx) -> {
            invoked.incrementAndGet();
            return ToolResult.success(JSON.createArrayNode());
        });
        var limited = new McpSecurity() {
            public McpContext authenticate(HttpServletRequest request, Instant deadline, BooleanSupplier cancelled) {
                return security.authenticate(request, deadline, cancelled);
            }
            public void authorize(McpContext context, String method, String name) { throw new McpRateLimitException(5); }
            public boolean visible(McpContext context, String name) { return true; }
        };
        try (var handler = new McpHttpHandler(new McpEngine(JSON.readTree("{\"name\":\"test\",\"version\":\"1\"}"),
                List.of(tool), McpAuthorization.permitAll()), defaults(), limited)) {
            var call = request("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}", 1);
            call.addHeader("Mcp-Param-Text", "hello");
            var response = send(handler, call);
            assertError(response, 429, -32000);
            assertThat(response.getHeader("Retry-After")).isEqualTo("5");
            assertThat(invoked).hasValue(0);
        }
    }

    @Test void applicableNameHeaderIsRequiredEvenWhenToolParamsAreMalformed() throws Exception {
        try (var handler = handler(defaults(), echo())) {
            for (String params : List.of("{}", "{\"name\":null}", "{\"name\":123}"))
                assertError(send(handler, request("tools/call", params, 1)), 400, -32020);
        }
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void unexpectedFailuresDoNotLogOrReturnPrivateInputs(CapturedOutput output) throws Exception {
        var tool = new McpTool(descriptor(), (args, ctx) -> {
            throw new IllegalStateException("private-output-secret");
        });
        try (var handler = handler(defaults(), tool)) {
            var call = request("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"private-argument-secret\"}}", 1);
            call.addHeader("Mcp-Param-Text", "private-argument-secret");
            var response = send(handler, call);
            assertError(response, 200, -32603);
            assertThat(JSON.readTree(response.getContentAsByteArray()).path("error").path("data").path("correlationId").asString()).isNotBlank();
            assertThat(response.getContentAsString()).doesNotContain("private-argument-secret", "private-output-secret", "Bearer test");
            assertThat(output.getAll()).doesNotContain("private-argument-secret", "private-output-secret", "Bearer test");
        }
    }

    private McpHttpHandler handler(McpProperties properties, McpTool tool) {
        return new McpHttpHandler(new McpEngine(JSON.readTree("{\"name\":\"test\",\"version\":\"1\"}"),
                List.of(tool), security), properties, security);
    }
    private McpHttpHandler handler(McpProperties properties, McpModule module) {
        return new McpHttpHandler(new McpEngine(JSON.readTree("{\"name\":\"test\",\"version\":\"1\"}"),
                List.of(), security, List.of(module)), properties, security);
    }
    private McpModule taskRoutingModule() {
        return new McpModule() {
            public ObjectNode capabilities() {
                ObjectNode capabilities = JSON.createObjectNode();
                capabilities.putObject("extensions").putObject(McpInteractions.TASKS_EXTENSION);
                return capabilities;
            }
            public Collection<McpMethod> methods() {
                return List.of("tasks/get", "tasks/update", "tasks/cancel").stream()
                        .map(name -> new McpMethod(name, null, null, (params, context) -> JSON.createObjectNode()
                                .put("taskId", params.path("taskId").asString())
                                .put("status", "working")))
                        .toList();
            }
        };
    }
    private McpTool echo() {
        return new McpTool(descriptor(), (args, context) -> ToolResult.success(JSON.createArrayNode()
                .add(JSON.createObjectNode().put("type", "text").put("text", args.path("text").asString()))));
    }
    private ObjectNode descriptor() {
        return (ObjectNode) JSON.readTree("""
                {"name":"echo","inputSchema":{"type":"object","properties":{"text":{"type":"string","x-mcp-header":"Text"}},"required":["text"]}}
                """);
    }
    private McpProperties defaults() {
        return new McpProperties(true, "/mcp", "test", "1", 1048576, 64, Duration.ofSeconds(5), 4, List.of());
    }
    private MockHttpServletRequest request(String method, String fields, Object id) {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType("application/json");
        request.addHeader("Accept", "application/json, text/event-stream");
        request.addHeader("Authorization", "Bearer test");
        request.addHeader("MCP-Protocol-Version", VERSION);
        request.addHeader("Mcp-Method", method);
        if ("tools/call".equals(method)) request.addHeader("Mcp-Name", "echo");
        var params = (ObjectNode) JSON.readTree(fields);
        params.set("_meta", JSON.createObjectNode().put("io.modelcontextprotocol/protocolVersion", VERSION)
                .set("io.modelcontextprotocol/clientCapabilities", JSON.createObjectNode()));
        request.setContent(JSON.writeValueAsBytes(JSON.createObjectNode().put("jsonrpc", "2.0")
                .set("id", JSON.valueToTree(id)).put("method", method).set("params", params)));
        return request;
    }
    private MockHttpServletResponse send(McpHttpHandler handler, MockHttpServletRequest request) throws Exception {
        request.setContent(request.getContentAsByteArray());
        var response = new MockHttpServletResponse();
        handler.handleRequest(request, response);
        return response;
    }
    private void assertError(MockHttpServletResponse response, int status, int code) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(JSON.readTree(response.getContentAsByteArray()).path("error").path("code").asInt()).isEqualTo(code);
    }

    private static final class DisconnectingResponse extends MockHttpServletResponse {
        private final ServletOutputStream output = new ServletOutputStream() {
            public boolean isReady() { return true; }
            public void setWriteListener(WriteListener listener) {}
            public void write(int value) throws IOException { throw new IOException("client disconnected"); }
        };

        @Override public ServletOutputStream getOutputStream() { return output; }
    }
}
