package io.github.enryold.mcp;

import java.util.Collection;
import tools.jackson.databind.node.ObjectNode;

/** A host-owned optional server capability and its explicitly registered handlers. */
public interface McpModule {
    ObjectNode capabilities();
    Collection<McpMethod> methods();
}
