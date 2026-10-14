package io.github.enryold.mcp.sample;

import io.github.enryold.mcp.McpAccessException;
import io.github.enryold.mcp.McpContext;
import io.github.enryold.mcp.McpProtocolException;
import io.github.enryold.mcp.McpRateLimitException;
import io.github.enryold.mcp.spring.McpSecurity;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Authentication and fixture credential mapping used only by official conformance diagnostics. */
@Configuration(proxyBeanMethods = false)
@Profile("conformance")
class ConformanceSecurityConfiguration {
    @Bean
    @Primary
    ConformanceMcpSecurity conformanceMcpSecurity() {
        return new ConformanceMcpSecurity();
    }
}

final class ConformanceMcpSecurity extends EchoService implements McpSecurity {
    private static final Set<String> MUTATIONS = Set.of("fixture.item.create", "fixture.item.createSlow");
    private static final Identity DIAGNOSTIC = new Identity("conformance", "red", Set.of("read", "write"));

    @Override
    public McpContext authenticate(
            HttpServletRequest request, Instant deadline, BooleanSupplier cancellation) {
        try {
            String authorization = request.getHeader("Authorization");
            Identity identity = authorization == null
                    ? DIAGNOSTIC
                    : authenticateMcp(authorization);
            return new McpContext(identity.subject(), identity.tenantId(), identity.scopes(),
                    Map.of(), deadline, cancellation);
        } catch (FixtureFailure rejected) {
            throw new McpProtocolException(401, -32001, "Unauthorized", null, null);
        }
    }

    @Override
    public String apiAuthorization(String subject, String tenantId, Set<String> scopes) {
        return DIAGNOSTIC.equals(new Identity(subject, tenantId, scopes))
                ? "Bearer api-alice-write"
                : super.apiAuthorization(subject, tenantId, scopes);
    }

    @Override
    public void authorize(McpContext context, String method, String name) {
        if (context.subject().equals("rate-limited")) throw new McpRateLimitException(5);
        if (!context.scopes().contains("read")
                || name != null && MUTATIONS.contains(name) && !context.scopes().contains("write")) {
            throw new McpAccessException("Forbidden");
        }
    }

    @Override
    public boolean visible(McpContext context, String name) {
        return context.scopes().contains("read")
                && (!MUTATIONS.contains(name) || context.scopes().contains("write"));
    }
}
