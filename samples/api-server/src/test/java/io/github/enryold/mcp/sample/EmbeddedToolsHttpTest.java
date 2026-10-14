package io.github.enryold.mcp.sample;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;
import io.github.enryold.mcp.spring.McpSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "enryold.mcp.enabled=true", "fixture.direct-service=true"})
class EmbeddedToolsHttpTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired McpSecurity security;
    @Autowired EchoService fixtures;

    @Test void directSpringServicesHaveEquivalentResultsAndOneMutation() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var echo = call(client, "fixture.echo", "alice-read", "{\"text\":\"Caffè ☕\"}");
            assertThat(echo.path("result").path("structuredContent")).isEqualTo(JSON.readTree("{\"text\":\"Caffè ☕\"}"));
            var created = call(client, "fixture.item.create", "alice-write", "{\"id\":\"embedded\",\"value\":\"created\"}");
            assertThat(created.path("result").path("structuredContent").path("tenantId").asString()).isEqualTo("red");
            var read = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/items/embedded"))
                    .header("Authorization", "Bearer api-alice-read").build(), HttpResponse.BodyHandlers.ofString());
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(created.path("result").path("structuredContent")).isEqualTo(JSON.readTree(read.body()));
            var state = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/test/state"))
                    .header("Authorization", "Bearer api-alice-read").build(), HttpResponse.BodyHandlers.ofString());
            assertThat(JSON.readTree(state.body()).path("counts").path("create").asInt()).isEqualTo(1);
            var blue = call(client, "fixture.item.read", "bob-write", "{\"id\":\"seed\"}");
            assertThat(blue.path("result").path("structuredContent").path("tenantId").asString()).isEqualTo("blue");
            var failure = call(client, "fixture.error", "alice-read", "{\"kind\":\"expected\"}");
            assertThat(failure.path("result").path("isError").asBoolean()).isTrue();
        }
    }

    @Test void separatesMcpAndFixtureApiCredentialsWithoutRetainingTheInboundBearer() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer alice-read");
        var context = security.authenticate(request, Instant.now().plusSeconds(1), () -> false);
        assertThat(context.attributes()).isEmpty();
        assertThat(fixtures.apiAuthorization(context.subject(), context.tenant(), context.scopes()))
                .isEqualTo("Bearer api-alice-read");
        assertThatThrownBy(() -> fixtures.apiAuthorization("alice", "blue", Set.of("read")))
                .isInstanceOf(EchoService.FixtureFailure.class);

        try (var client = HttpClient.newHttpClient()) {
            var restWithMcpToken = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/items/seed"))
                    .header("Authorization", "Bearer alice-read").build(), HttpResponse.BodyHandlers.discarding());
            assertThat(restWithMcpToken.statusCode()).isEqualTo(401);
            var restWithBlindPrefix = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/items/seed"))
                    .header("Authorization", "Bearer api-api-alice-read").build(), HttpResponse.BodyHandlers.discarding());
            assertThat(restWithBlindPrefix.statusCode()).isEqualTo(401);
            assertThat(callResponse(client, "fixture.item.read", "api-alice-read", "{\"id\":\"seed\"}").statusCode())
                    .isEqualTo(401);
        }
    }
    private String base() { return "http://127.0.0.1:" + port; }
    private JsonNode call(HttpClient client, String name, String token, String arguments) throws Exception {
        var response = callResponse(client, name, token, arguments);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
    private HttpResponse<String> callResponse(HttpClient client, String name, String token, String arguments) throws Exception {
        var params = JSON.createObjectNode().put("name", name).set("arguments", JSON.readTree(arguments));
        params.set("_meta", JSON.createObjectNode().put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                .set("io.modelcontextprotocol/clientCapabilities", JSON.createObjectNode()));
        var body = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", "embedded").put("method", "tools/call").set("params", params);
        return client.send(HttpRequest.newBuilder(URI.create(base() + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", "tools/call").header("Mcp-Name", name)
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
