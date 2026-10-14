package io.github.enryold.mcp.sample;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import jakarta.servlet.http.HttpServletRequest;
import io.github.enryold.mcp.*;
import io.github.enryold.mcp.spring.McpSecurity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Invented identities and REST forwarding belong only to this loopback fixture. */
@Configuration(proxyBeanMethods = false)
class SampleMcpConfiguration {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JsonNode contract;
    private final String fixtureUrl;
    private final EchoService service;
    private final boolean directService;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    SampleMcpConfiguration(@Value("${fixture.base-url:http://127.0.0.1:18080}") String fixtureUrl,
            EchoService service, @Value("${fixture.direct-service:false}") boolean directService) throws Exception {
        this.fixtureUrl = fixtureUrl;
        this.service = service;
        this.directService = directService;
        try (var source = SampleMcpConfiguration.class.getResourceAsStream("/tool-contract.json")) {
            if (source == null) throw new IllegalStateException("Missing fixture tool contract");
            contract = JSON.readTree(source);
        }
    }

    @Bean @ConditionalOnMissingBean(McpSecurity.class)
    McpSecurity fixtureMcpSecurity(EchoService fixtures) {
        return new McpSecurity() {
            public McpContext authenticate(HttpServletRequest request, Instant deadline, BooleanSupplier cancellation) {
                String bearer = request.getHeader("Authorization");
                try {
                    var identity = fixtures.authenticateMcp(bearer);
                    return new McpContext(identity.subject(), identity.tenantId(), identity.scopes(),
                            Map.of(), deadline, cancellation);
                } catch (EchoService.FixtureFailure rejected) {
                    throw new McpProtocolException(401, -32001, "Unauthorized", null, null);
                }
            }
            public void authorize(McpContext context, String method, String name) {
                if (context.subject().equals("rate-limited")) throw new McpRateLimitException(5);
                if (!context.scopes().contains("read") || name != null
                        && Set.of("fixture.item.create", "fixture.item.createSlow").contains(name)
                        && !context.scopes().contains("write"))
                    throw new McpAccessException("Forbidden");
            }
            public boolean visible(McpContext context, String name) {
                return context.scopes().contains("read")
                        && (!Set.of("fixture.item.create", "fixture.item.createSlow").contains(name) || context.scopes().contains("write"));
            }
        };
    }

    @Bean McpTool echoTool() { return tool("fixture.echo", "GET", "/api/echo"); }
    @Bean McpTool errorTool() { return tool("fixture.error", "POST", "/api/error"); }
    @Bean McpTool createItemTool() { return tool("fixture.item.create", "POST", "/api/items"); }
    @Bean McpTool createSlowItemTool() { return tool("fixture.item.createSlow", "POST", "/api/items/slow"); }
    @Bean McpTool readItemTool() { return tool("fixture.item.read", "GET", "/api/items"); }
    @Bean McpTool slowTool() { return tool("fixture.slow", "POST", "/api/slow"); }

    private McpTool tool(String name, String method, String path) {
        ObjectNode descriptor = null;
        for (JsonNode tool : contract.path("tools")) {
            if (name.equals(tool.path("name").asString())) descriptor = (ObjectNode) tool.deepCopy();
        }
        if (descriptor == null) throw new IllegalArgumentException("Missing fixture descriptor: " + name);
        descriptor.remove("requiredScopes");
        return new McpTool(descriptor, (arguments, context) -> {
            if (directService) return invokeDirect(name, arguments, context);
            String route = path;
            if (name.equals("fixture.echo")) route += "?text=" + encode(arguments.path("text").asString());
            if (name.equals("fixture.item.read")) route += "/" + encode(arguments.path("id").asString());
            Duration remaining = Duration.between(Instant.now(), context.deadline());
            if (remaining.isNegative() || remaining.isZero() || context.isCancelled())
                throw new McpProtocolException(408, -32000, "Request deadline exceeded", null, null);
            var request = HttpRequest.newBuilder(URI.create(fixtureUrl + route)).timeout(remaining)
                    .header("Authorization", service.apiAuthorization(
                            context.subject(), context.tenant(), context.scopes()));
            if (method.equals("POST")) request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(arguments)));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            JsonNode value = JSON.readTree(response.body());
            var content = JSON.createArrayNode().add(JSON.createObjectNode()
                    .put("type", "text").put("text", JSON.writeValueAsString(value)));
            return response.statusCode() >= 200 && response.statusCode() < 300
                    ? ToolResult.success(content, value) : ToolResult.error(content);
        });
    }
    private ToolResult invokeDirect(String name, ObjectNode arguments, McpContext context) {
        var identity = service.trustedMcpIdentity(context.subject(), context.tenant(), context.scopes());
        try {
            Object value = switch (name) {
                case "fixture.echo" -> service.echo(identity, arguments.path("text").asString());
                case "fixture.item.read" -> service.read(identity, arguments.path("id").asString());
                case "fixture.item.create" -> service.create(identity,
                        JSON.treeToValue(arguments, EchoService.CreateItem.class));
                case "fixture.item.createSlow" -> service.createThenDelay(identity,
                        JSON.treeToValue(arguments, EchoService.SlowCreateItem.class));
                case "fixture.slow" -> service.slow(identity, JSON.treeToValue(arguments, EchoService.DelayRequest.class));
                case "fixture.error" -> {
                    service.fail(identity, JSON.treeToValue(arguments, EchoService.FailureRequest.class));
                    yield null;
                }
                default -> throw new IllegalArgumentException("Unknown fixture tool");
            };
            JsonNode structured = JSON.valueToTree(value);
            return ToolResult.success(text(structured), structured);
        } catch (EchoService.FixtureFailure failure) {
            return ToolResult.error(text(JSON.createObjectNode().put("code", failure.code()).put("message", failure.getMessage())));
        } catch (IllegalStateException unexpected) {
            return ToolResult.error(text(JSON.createObjectNode().put("code", "internal_error").put("message", "Unexpected fixture failure")));
        }
    }
    private tools.jackson.databind.node.ArrayNode text(JsonNode value) {
        return JSON.createArrayNode().add(JSON.createObjectNode().put("type", "text").put("text", JSON.writeValueAsString(value)));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
