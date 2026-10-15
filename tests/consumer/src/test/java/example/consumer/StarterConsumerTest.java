package example.consumer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import jakarta.servlet.http.HttpServletRequest;
import io.github.enryold.mcp.*;
import io.github.enryold.mcp.spring.McpHttpHandler;
import io.github.enryold.mcp.spring.McpSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class StarterConsumerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void starterResolvesAndServesFromOrdinaryPublishedJars() throws Exception {
        assertThat(SpringBootVersion.getVersion()).isEqualTo(System.getProperty("expectedBootVersion"));
        try (var context = new SpringApplicationBuilder(Consumer.class).properties(
                "server.port=0", "server.address=127.0.0.1", "spring.main.banner-mode=off",
                "enryold.mcp.enabled=true", "enryold.mcp.path=/tools", "enryold.mcp.server-name=consumer").run();
             var client = HttpClient.newHttpClient()) {
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/tools"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", "2026-07-28")
                    .header("Mcp-Method", "tools/call").header("Mcp-Name", "echo")
                    .header("Authorization", "Bearer consumer")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":"consumer","method":"tools/call","params":{
                          "name":"echo","arguments":{"text":"Caffè ☕"},"_meta":{
                            "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                            "io.modelcontextprotocol/clientCapabilities":{}}}}
                        """)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            var body = JSON.readTree(response.body());
            assertThat(body.path("result").path("structuredContent").path("text").asString()).isEqualTo("Caffè ☕");
            assertThat(body.path("id").asString()).isEqualTo("consumer");
            assertThat(context.getBean(McpEngine.class).getClass().getProtectionDomain().getCodeSource()
                    .getLocation().toString()).endsWith(System.getProperty("expectedCoreJar"));
        }
    }

    @Test void disabledStarterExposesNoHandler() {
        try (var context = new SpringApplicationBuilder(Consumer.class).properties(
                "server.port=0", "server.address=127.0.0.1", "spring.main.banner-mode=off").run()) {
            assertThat(context.getBeansOfType(McpHttpHandler.class)).isEmpty();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Consumer {
        @Bean McpTool echo() {
            var descriptor = (ObjectNode) JSON.readTree("""
                {"name":"echo","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},
                 "required":["text"],"additionalProperties":false},"outputSchema":{"type":"object"}}
                """);
            return new McpTool(descriptor, (args, context) -> ToolResult.success(JSON.createArrayNode()
                    .add(JSON.createObjectNode().put("type", "text").put("text", args.path("text").asString())), args));
        }
        @Bean McpSecurity security() {
            return new McpSecurity() {
                public McpContext authenticate(HttpServletRequest request, Instant deadline, BooleanSupplier cancel) {
                    if (!"Bearer consumer".equals(request.getHeader("Authorization")))
                        throw new McpProtocolException(401, -32001, "Unauthorized", null, null);
                    return new McpContext("consumer", "tenant", Set.of("read"), Map.of(), deadline, cancel);
                }
                public void authorize(McpContext context, String method, String name) {}
                public boolean visible(McpContext context, String name) { return true; }
            };
        }
    }
}
