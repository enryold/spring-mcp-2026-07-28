package io.github.enryold.mcp;

public final class McpRateLimitException extends RuntimeException {
    private final int retryAfterSeconds;

    public McpRateLimitException(int retryAfterSeconds) {
        super("Rate limit exceeded");
        if (retryAfterSeconds < 1 || retryAfterSeconds > 86_400) {
            throw new IllegalArgumentException("retryAfterSeconds must be between 1 and 86400");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
