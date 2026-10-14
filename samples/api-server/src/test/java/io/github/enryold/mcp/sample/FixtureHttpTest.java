package io.github.enryold.mcp.sample;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fixture.reset-key=test-reset-key")
class FixtureHttpTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Test
    void isolatesTenantStateAndCountsMutationsExactlyOnce() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertThat(send(client, "POST", "/api/test/reset", "api-alice-write", "{}", true).statusCode()).isEqualTo(204);

            var created = send(client, "POST", "/api/items", "api-alice-write", "{\"id\":\"café\",\"value\":\"☕\"}", false);
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(json(created).path("tenantId").asString()).isEqualTo("red");
            assertThat(json(created).path("createdAt").asString()).isEqualTo("2026-10-14T00:00:00Z");

            assertThat(send(client, "GET", "/api/items/caf%C3%A9", "api-alice-read", null, false).statusCode()).isEqualTo(200);
            assertThat(send(client, "GET", "/api/items/caf%C3%A9", "api-bob-write", null, false).statusCode()).isEqualTo(404);

            JsonNode state = json(send(client, "GET", "/api/test/state", "api-alice-read", null, false));
            assertThat(state.path("counts").path("create").asInt()).isEqualTo(1);
            assertThat(state.path("items").size()).isEqualTo(2);
        }
    }

    @Test
    void enforcesFixtureIdentityPermissionsAndFailureShapes() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertThat(send(client, "POST", "/api/test/reset", "api-alice-write", "{}", false).statusCode()).isEqualTo(403);
            assertThat(send(client, "POST", "/api/items", "api-alice-read", "{\"id\":\"x\",\"value\":\"x\"}", false).statusCode())
                    .isEqualTo(403);
            assertThat(send(client, "GET", "/api/items/seed", "alice-read", null, false).statusCode()).isEqualTo(401);
            assertThat(send(client, "GET", "/api/items/seed", "revoked", null, false).statusCode()).isEqualTo(401);
            assertThat(send(client, "GET", "/api/items/seed", null, null, false).statusCode()).isEqualTo(401);

            var expected = send(client, "POST", "/api/error", "api-alice-read", "{\"kind\":\"expected\"}", false);
            assertThat(expected.statusCode()).isEqualTo(409);
            assertThat(json(expected).path("code").asString()).isEqualTo("fixture_conflict");

            var unexpected = send(client, "POST", "/api/error", "api-alice-read", "{\"kind\":\"unexpected\"}", false);
            assertThat(unexpected.statusCode()).isEqualTo(500);
            assertThat(unexpected.body()).doesNotContain("fixture exploded");
        }
    }

    @Test
    void slowMutationCommitsExactlyOnceBeforeItsDelayedResponse() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertThat(send(client, "POST", "/api/test/reset", "api-alice-write", "{}", true).statusCode()).isEqualTo(204);
            var created = send(client, "POST", "/api/items/slow", "api-alice-write",
                    "{\"id\":\"committed\",\"value\":\"visible\",\"delayMs\":5}", false);
            assertThat(created.statusCode()).isEqualTo(201);

            JsonNode state = json(send(client, "GET", "/api/test/state", "api-alice-read", null, false));
            assertThat(state.path("counts").path("create").asInt()).isEqualTo(1);
            assertThat(state.path("items").toString()).contains("committed", "visible");
        }
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String token, String body,
            boolean reset) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (reset) {
            builder.header("X-Fixture-Reset-Key", "test-reset-key");
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }
}
