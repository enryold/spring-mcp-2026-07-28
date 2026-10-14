package io.github.enryold.mcp;

import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/** Explicit direct resource registration. */
public final class McpResource {
    private final ObjectNode descriptor;
    private final Reader reader;

    public McpResource(ObjectNode descriptor, Reader reader) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor").deepCopy();
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public String uri() {
        return descriptor.path("uri").asText();
    }

    public ObjectNode descriptor() {
        return descriptor.deepCopy();
    }

    public Reader reader() {
        return reader;
    }

    @FunctionalInterface
    public interface Reader {
        ObjectNode read(String uri, McpContext context) throws Exception;
    }
}
