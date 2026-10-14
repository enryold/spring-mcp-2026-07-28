package io.github.enryold.mcp.spring;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import io.github.enryold.mcp.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Executable OAuth host example: local issuer/JWKS, host-owned filters and trusted tenant claims. */
class OAuthResourceServerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void validatesJwtIdentityAudienceIssuerExpiryScopesAndKeepsHostCsrf() throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("local-test-key").generate();
        var issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer.createContext("/jwks", exchange -> {
            byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var output = exchange.getResponseBody()) { output.write(jwks); }
        });
        issuer.start();
        String issuerUrl = "http://127.0.0.1:" + issuer.getAddress().getPort();
        try (var context = new SpringApplicationBuilder(OAuthHost.class).properties(
                "server.port=0", "server.address=127.0.0.1", "spring.main.banner-mode=off",
                "enryold.mcp.enabled=true", "test.issuer=" + issuerUrl).run();
             var client = HttpClient.newHttpClient()) {
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            String base = "http://127.0.0.1:" + port;
            var valid = call(client, base, token(signingKey, issuerUrl, "https://mcp.example/mcp", "read", "red", Instant.now().plusSeconds(300)));
            assertThat(valid.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(valid.body()).path("result").path("structuredContent").path("tenant").asString()).isEqualTo("red");
            assertThat(JSON.readTree(valid.body()).path("result").path("structuredContent").path("subject").asString()).isEqualTo("alice");
            assertThat(valid.headers().firstValue("mcp-session-id")).isEmpty();
            var blue = call(client, base, token(signingKey, issuerUrl, "https://mcp.example/mcp", "read", "blue", Instant.now().plusSeconds(300)));
            assertThat(JSON.readTree(blue.body()).path("result").path("structuredContent").path("tenant").asString()).isEqualTo("blue");
            assertThat(call(client, base, token(signingKey, issuerUrl, "wrong", "read", "red", Instant.now().plusSeconds(300))).statusCode()).isEqualTo(401);
            assertThat(call(client, base, token(signingKey, issuerUrl + "/wrong", "https://mcp.example/mcp", "read", "red", Instant.now().plusSeconds(300))).statusCode()).isEqualTo(401);
            assertThat(call(client, base, token(signingKey, issuerUrl, "https://mcp.example/mcp", "read", "red", Instant.now().minusSeconds(300))).statusCode()).isEqualTo(401);
            var denied = call(client, base, token(signingKey, issuerUrl, "https://mcp.example/mcp", "write", "red", Instant.now().plusSeconds(300)));
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.headers().firstValue("www-authenticate").orElseThrow())
                    .contains("error=\"insufficient_scope\"", "scope=\"read\"");
            var missing = call(client, base, null);
            assertThat(missing.statusCode()).isEqualTo(401);
            assertThat(missing.headers().firstValue("www-authenticate").orElseThrow()).contains("resource_metadata=");
            var metadata = client.send(HttpRequest.newBuilder(URI.create(base + "/.well-known/oauth-protected-resource"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(metadata.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(metadata.body()).path("resource").asString()).isEqualTo("https://mcp.example/mcp");
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/public")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/public")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        } finally { issuer.stop(0); }
    }

    private HttpResponse<String> call(HttpClient client, String base, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", "tools/call").header("Mcp-Name", "whoami");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"whoami","arguments":{},
                 "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},
                          "tenant":"forged"}}}
                """)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String token(RSAKey key, String issuer, String audience, String scopes, String tenant, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("alice")
                .issueTime(new Date()).expirationTime(Date.from(expiry)).claim("scope", scopes).claim("tenant", tenant).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @SpringBootConfiguration @EnableAutoConfiguration
    static class OAuthHost {
        @Bean @Order(0) SecurityFilterChain mcpChain(HttpSecurity http, @Value("${test.issuer}") String issuer) throws Exception {
            return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource")
                    .authorizeHttpRequests(auth -> auth.requestMatchers("/.well-known/oauth-protected-resource").permitAll()
                            .anyRequest().authenticated())
                    .csrf(csrf -> csrf.ignoringRequestMatchers("/mcp"))
                    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})
                            .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(builder ->
                                    builder.resource("https://mcp.example/mcp").authorizationServer(issuer).scope("read")))).build();
        }
        @Bean @Order(1) SecurityFilterChain hostChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.requestMatchers("/public").permitAll().anyRequest().denyAll()).build();
        }
        @Bean JwtDecoder jwtDecoder(@Value("${test.issuer}") String issuer) {
            var decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/jwks").build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(JwtValidators.createDefaultWithIssuer(issuer),
                    jwt -> jwt.getAudience().contains("https://mcp.example/mcp") ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null))));
            return decoder;
        }
        @Bean McpSecurity security() {
            var policy = new McpAuthorization() {
                public void authorize(McpContext context, String method, String name) {
                    if (!context.scopes().contains("read")) throw new McpAccessException("Forbidden");
                }
                public boolean visible(McpContext context, String name) { return context.scopes().contains("read"); }
            };
            return new SpringSecurityMcpSecurity((authentication, deadline, cancelled) -> {
                var jwt = ((JwtAuthenticationToken) authentication).getToken();
                Set<String> scopes = Set.copyOf(java.util.Arrays.asList(jwt.getClaimAsString("scope").split(" ")));
                return new McpContext(jwt.getSubject(), jwt.getClaimAsString("tenant"), scopes, Map.of(), deadline, cancelled);
            }, policy, (request, rejection) -> rejection.httpStatus() == 403
                    ? "Bearer error=\"insufficient_scope\", scope=\"read\"" : "Bearer");
        }
        @Bean McpTool whoami() {
            return new McpTool((ObjectNode) JSON.readTree("""
                    {"name":"whoami","inputSchema":{"type":"object","additionalProperties":false},
                     "outputSchema":{"type":"object","properties":{"subject":{"type":"string"},"tenant":{"type":"string"}},
                       "required":["subject","tenant"],"additionalProperties":false}}
                    """), (args, context) -> {
                var identity = JSON.createObjectNode().put("subject", context.subject()).put("tenant", context.tenant());
                return ToolResult.success(JSON.createArrayNode().add(JSON.createObjectNode().put("type", "text")
                        .put("text", JSON.writeValueAsString(identity))), identity);
            });
        }
        @Bean HostRoutes routes() { return new HostRoutes(); }
    }
    @RestController
    static class HostRoutes {
        @GetMapping("/public") String get() { return "public"; }
        @PostMapping("/public") String post() { return "public"; }
    }
}
