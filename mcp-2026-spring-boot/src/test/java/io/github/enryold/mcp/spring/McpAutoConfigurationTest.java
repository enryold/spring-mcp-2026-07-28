package io.github.enryold.mcp.spring;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import jakarta.servlet.http.HttpServletRequest;
import io.github.enryold.mcp.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class McpAutoConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(McpAutoConfiguration.class));
    private final McpSecurity security = new McpSecurity() {
        public McpContext authenticate(HttpServletRequest request, Instant deadline, BooleanSupplier cancel) {
            return new McpContext("subject", "tenant", Set.of(), Map.of(), deadline, cancel);
        }
        public void authorize(McpContext context, String method, String name) {}
        public boolean visible(McpContext context, String name) { return true; }
    };

    @Test void disabledByDefaultExposesNoEndpoint() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(McpHttpHandler.class)
                .doesNotHaveBean("mcpHandlerMapping"));
        runner.withPropertyValues("enryold.mcp.enabled=false").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(McpHttpHandler.class));
    }
    @Test void enablingRequiresExplicitSecurityIntegration() {
        runner.withPropertyValues("enryold.mcp.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("McpSecurity");
        });
    }
    @Test void configuredRouteAndConsumerBeansBackOff() {
        runner.withPropertyValues("enryold.mcp.enabled=true", "enryold.mcp.path=/custom")
                .withBean(McpSecurity.class, () -> security).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(McpEngine.class).hasSingleBean(McpHttpHandler.class);
                    assertThat(context.getBean("mcpHandlerMapping", SimpleUrlHandlerMapping.class).getUrlMap())
                            .containsOnlyKeys("/custom");
                });
        var engine = new McpEngine(JsonMapper.builder().build().readTree("{\"name\":\"custom\",\"version\":\"1\"}"),
                List.of(), security);
        runner.withPropertyValues("enryold.mcp.enabled=true").withBean(McpSecurity.class, () -> security)
                .withBean(McpEngine.class, () -> engine).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(McpEngine.class);
                    assertThat(context.getBean(McpEngine.class)).isSameAs(engine);
                });
    }
    @Test void absentCoreAndWebAndNonServletApplicationDoNotLoadConfiguration() {
        for (String absent : List.of("io.github.enryold.mcp", "org.springframework.web.servlet", "jakarta.servlet.http")) {
            runner.withPropertyValues("enryold.mcp.enabled=true").withClassLoader(new FilteredClassLoader(absent))
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("mcpHandlerMapping"));
        }
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(McpAutoConfiguration.class))
                .withPropertyValues("enryold.mcp.enabled=true").run(context ->
                        assertThat(context).hasNotFailed().doesNotHaveBean("mcpHandlerMapping"));
    }
    @Test void duplicateToolRegistrationFailsStartup() {
        var descriptor = (tools.jackson.databind.node.ObjectNode) JsonMapper.builder().build().readTree(
                "{\"name\":\"duplicate\",\"inputSchema\":{\"type\":\"object\"}}");
        var tool = new McpTool(descriptor, (args, ctx) -> ToolResult.success(JsonMapper.builder().build().createArrayNode()));
        runner.withPropertyValues("enryold.mcp.enabled=true").withBean(McpSecurity.class, () -> security)
                .withBean("first", McpTool.class, () -> tool).withBean("second", McpTool.class, () -> tool)
                .run(context -> assertThat(context).hasFailed());
    }
}
