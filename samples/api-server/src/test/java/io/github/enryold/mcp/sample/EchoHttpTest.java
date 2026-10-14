package io.github.enryold.mcp.sample;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EchoHttpTest {
    @LocalServerPort
    private int port;

    @Test
    void fixturePreservesUnicodeAndRequiresTheInput() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            String text = "Caffè ☕ & MCP";
            var response = client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + "/api/echo?text="
                            + URLEncoder.encode(text, StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer api-alice-read").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JsonMapper.builder().build().readTree(response.body()).path("text").asString()).isEqualTo(text);

            var missingInput = client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + "/api/echo"))
                    .header("Authorization", "Bearer api-alice-read").build(), HttpResponse.BodyHandlers.discarding());
            assertThat(missingInput.statusCode()).isEqualTo(400);
        }
    }
}
