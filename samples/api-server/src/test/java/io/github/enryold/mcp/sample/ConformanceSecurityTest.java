package io.github.enryold.mcp.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enryold.mcp.McpAccessException;
import io.github.enryold.mcp.McpProtocolException;
import io.github.enryold.mcp.McpRateLimitException;
import io.github.enryold.mcp.spring.McpSecurity;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

@SpringBootTest(properties = {
        "spring.profiles.active=conformance", "enryold.mcp.enabled=true"})
class ConformanceSecurityTest {
    @Autowired McpSecurity security;
    @Autowired EchoService fixtures;

    @Test
    void authenticatesSuppliedCredentialsAndTrustsOnlyTheHeaderlessDiagnosticIdentity() {
        var anonymous = authenticate(null);
        assertThat(anonymous.subject()).isEqualTo("conformance");
        assertThat(anonymous.tenant()).isEqualTo("red");
        assertThat(anonymous.scopes()).isEqualTo(Set.of("read", "write"));
        assertThat(fixtures.apiAuthorization(anonymous.subject(), anonymous.tenant(), anonymous.scopes()))
                .isEqualTo("Bearer api-alice-write");
        var forwardedIdentity = fixtures.authenticate(
                fixtures.apiAuthorization(anonymous.subject(), anonymous.tenant(), anonymous.scopes()));
        assertThat(forwardedIdentity.subject()).isEqualTo("alice");
        assertThat(forwardedIdentity.tenantId()).isEqualTo("red");
        assertThat(forwardedIdentity.scopes()).isEqualTo(Set.of("read", "write"));

        var alice = authenticate("Bearer alice-read");
        assertThat(alice.subject()).isEqualTo("alice");
        assertThat(alice.tenant()).isEqualTo("red");
        assertThat(alice.scopes()).containsExactly("read");
        assertThat(security.visible(alice, "fixture.item.create")).isFalse();
        assertThatThrownBy(() -> security.authorize(alice, "tools/call", "fixture.item.create"))
                .isInstanceOf(McpAccessException.class);

        var bob = authenticate("Bearer bob-write");
        assertThat(bob.tenant()).isEqualTo("blue");
        assertThat(security.visible(bob, "fixture.item.create")).isTrue();

        var limited = authenticate("Bearer alice-rate-limited");
        assertThatThrownBy(() -> security.authorize(limited, "tools/call", "fixture.echo"))
                .isInstanceOf(McpRateLimitException.class);
        assertThatThrownBy(() -> authenticate("Bearer revoked"))
                .isInstanceOfSatisfying(McpProtocolException.class,
                        rejected -> assertThat(rejected.httpStatus()).isEqualTo(401));
        assertThatThrownBy(() -> authenticate(""))
                .isInstanceOf(McpProtocolException.class);
    }

    private io.github.enryold.mcp.McpContext authenticate(String authorization) {
        var request = new MockHttpServletRequest();
        if (authorization != null) request.addHeader("Authorization", authorization);
        return security.authenticate(request, Instant.now().plusSeconds(1), () -> false);
    }
}
