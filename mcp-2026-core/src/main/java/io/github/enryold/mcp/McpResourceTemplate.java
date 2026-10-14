package io.github.enryold.mcp;

import java.util.Objects;
import java.util.function.Predicate;
import tools.jackson.databind.node.ObjectNode;

/** Explicit resource-template registration with host-owned URI matching. */
public final class McpResourceTemplate {
    private final ObjectNode descriptor;
    private final Predicate<String> matcher;
    private final McpResource.Reader reader;

    public McpResourceTemplate(ObjectNode descriptor, Predicate<String> matcher, McpResource.Reader reader) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor").deepCopy();
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public String uriTemplate() {
        return descriptor.path("uriTemplate").asText();
    }

    public ObjectNode descriptor() {
        return descriptor.deepCopy();
    }

    public boolean matches(String uri) {
        return matcher.test(uri);
    }

    public McpResource.Reader reader() {
        return reader;
    }
}
