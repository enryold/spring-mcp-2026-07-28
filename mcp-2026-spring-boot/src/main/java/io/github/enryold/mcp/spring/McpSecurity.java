package io.github.enryold.mcp.spring;

import java.time.Instant;
import java.util.function.BooleanSupplier;
import jakarta.servlet.http.HttpServletRequest;
import io.github.enryold.mcp.McpAuthorization;
import io.github.enryold.mcp.McpContext;
import io.github.enryold.mcp.McpProtocolException;

/** Host-owned authentication, operation authorization and catalog visibility. */
public interface McpSecurity extends McpAuthorization {
    McpContext authenticate(HttpServletRequest request, Instant deadline, BooleanSupplier cancellation);

    /** Override for host-specific OAuth scope and protected-resource metadata challenges. */
    default String wwwAuthenticate(HttpServletRequest request, McpProtocolException rejection) {
        if (rejection.httpStatus() == 401) return "Bearer";
        if (rejection.httpStatus() == 403 && rejection.code() == -32001)
            return "Bearer error=\"insufficient_scope\"";
        return null;
    }
}
