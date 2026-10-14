package io.github.enryold.mcp.spring;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class McpPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test void defaultsAreDisabledAndBounded() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(McpProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.path()).isEqualTo("/mcp");
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.allowedOrigins()).isEmpty();
        });
    }

    @Test void bindsDurationAndExactOrigins() {
        runner.withPropertyValues("enryold.mcp.path=/tools", "enryold.mcp.request-timeout=250ms",
                "enryold.mcp.allowed-origins[0]=https://client.example").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(McpProperties.class).requestTimeout()).isEqualTo(Duration.ofMillis(250));
            assertThat(context.getBean(McpProperties.class).allowedOrigins()).isEqualTo(List.of("https://client.example"));
        });
    }

    @Test void rejectsUnboundedAndAmbiguousSettings() {
        for (String property : List.of("max-request-bytes=0", "max-json-depth=0", "max-concurrent-requests=0",
                "request-timeout=0s", "request-timeout=2h", "path=relative", "path=/mcp/**",
                "allowed-origins[0]=*", "allowed-origins[0]=https://client.example/path")) {
            runner.withPropertyValues("enryold.mcp." + property).run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpProperties.class)
    static class PropertiesConfiguration {}
}
