package io.github.enryold.mcp.spring;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit opt-in and resource bounds for the MCP endpoint. */
@ConfigurationProperties("enryold.mcp")
public record McpProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("/mcp") String path,
        @DefaultValue("mcp-server") String serverName,
        @DefaultValue("0.1.0") String serverVersion,
        @DefaultValue("1048576") int maxRequestBytes,
        @DefaultValue("64") int maxJsonDepth,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("64") int maxConcurrentRequests,
        @DefaultValue List<String> allowedOrigins) {
    public McpProperties {
        if (path == null || !path.startsWith("/") || path.contains("*") || path.contains("?")
                || path.contains("#") || path.contains("{") || path.contains("..")
                || path.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("enryold.mcp.path must be a literal absolute path");
        }
        if (serverName == null || serverName.isBlank() || serverVersion == null || serverVersion.isBlank()) {
            throw new IllegalArgumentException("MCP server identity must not be blank");
        }
        if (maxRequestBytes < 1 || maxRequestBytes > 67108864 || maxJsonDepth < 2 || maxJsonDepth > 1024
                || maxConcurrentRequests < 1 || maxConcurrentRequests > 10000
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("MCP limits require bytes 1..67108864, depth 2..1024, concurrent 1..10000, timeout >0..1h");
        }
        allowedOrigins = List.copyOf(allowedOrigins == null ? List.of() : allowedOrigins);
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !uri.getRawPath().isEmpty()) {
                throw new IllegalArgumentException("MCP allowed-origins must contain exact HTTP origins");
            }
        }
    }
}
