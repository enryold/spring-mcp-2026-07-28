package io.github.enryold.mcp.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enryold.mcp.McpAuthorization;
import io.github.enryold.mcp.McpContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpringSecurityMcpSecurityTest {
    @Test
    void preservesMethodAwareVisibilityFromTheHostPolicy() {
        McpAuthorization policy = new McpAuthorization() {
            @Override public void authorize(McpContext context, String method, String name) {}
            @Override public boolean visible(McpContext context, String name) { return false; }
            @Override public boolean visible(McpContext context, String method, String name) {
                return "prompts/get".equals(method);
            }
        };
        var security = new SpringSecurityMcpSecurity((authentication, deadline, cancellation) -> context(), policy);

        assertThat(security.visible(context(), "prompts/get", "collision")).isTrue();
        assertThat(security.visible(context(), "tools/call", "collision")).isFalse();
    }

    private static McpContext context() {
        return new McpContext("subject", "tenant", Set.of("read"), Map.of(), Instant.MAX, () -> false);
    }
}
