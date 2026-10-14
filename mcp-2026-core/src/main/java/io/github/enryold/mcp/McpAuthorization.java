package io.github.enryold.mcp;

public interface McpAuthorization {
    void authorize(McpContext context, String method, String toolName);

    boolean visible(McpContext context, String toolName);

    /** Method-aware visibility for catalogs whose names may collide across protocol domains. */
    default boolean visible(McpContext context, String method, String name) {
        return visible(context, name);
    }

    static McpAuthorization permitAll() {
        return PermitAll.INSTANCE;
    }

    final class PermitAll implements McpAuthorization {
        private static final PermitAll INSTANCE = new PermitAll();

        private PermitAll() {}

        @Override
        public void authorize(McpContext context, String method, String toolName) {}

        @Override
        public boolean visible(McpContext context, String toolName) {
            return true;
        }
    }
}
