package io.github.enryold.mcp.spring;

import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import jakarta.servlet.http.HttpServletRequest;
import io.github.enryold.mcp.McpAuthorization;
import io.github.enryold.mcp.McpContext;
import io.github.enryold.mcp.McpProtocolException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the host's authenticated context on the request thread before worker dispatch. */
public final class SpringSecurityMcpSecurity implements McpSecurity {
    @FunctionalInterface
    public interface ContextFactory {
        McpContext create(Authentication authentication, Instant deadline, BooleanSupplier cancellation);
    }
    private final ContextFactory contexts;
    private final McpAuthorization authorization;
    private final BiFunction<HttpServletRequest, McpProtocolException, String> challenges;

    public SpringSecurityMcpSecurity(ContextFactory contexts, McpAuthorization authorization) {
        this(contexts, authorization, null);
    }
    public SpringSecurityMcpSecurity(ContextFactory contexts, McpAuthorization authorization,
            BiFunction<HttpServletRequest, McpProtocolException, String> challenges) {
        this.contexts = Objects.requireNonNull(contexts);
        this.authorization = Objects.requireNonNull(authorization);
        this.challenges = challenges;
    }
    @Override
    public McpContext authenticate(HttpServletRequest request, Instant deadline, BooleanSupplier cancellation) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken)
            throw new McpProtocolException(401, -32001, "Authentication required", null, null);
        return contexts.create(authentication, deadline, cancellation);
    }
    @Override public void authorize(McpContext context, String method, String toolName) {
        authorization.authorize(context, method, toolName);
    }
    @Override public boolean visible(McpContext context, String toolName) {
        return authorization.visible(context, toolName);
    }
    @Override public boolean visible(McpContext context, String method, String name) {
        return authorization.visible(context, method, name);
    }
    @Override public String wwwAuthenticate(HttpServletRequest request, McpProtocolException rejection) {
        return challenges == null ? McpSecurity.super.wwwAuthenticate(request, rejection) : challenges.apply(request, rejection);
    }
}
