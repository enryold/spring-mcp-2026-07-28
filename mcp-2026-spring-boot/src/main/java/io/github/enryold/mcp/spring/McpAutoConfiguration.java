package io.github.enryold.mcp.spring;

import java.util.List;
import java.util.Map;
import io.github.enryold.mcp.McpEngine;
import io.github.enryold.mcp.McpTool;
import io.github.enryold.mcp.McpModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration
@ConditionalOnClass(name = {"io.github.enryold.mcp.McpEngine", "jakarta.servlet.http.HttpServletRequest",
        "org.springframework.web.servlet.handler.SimpleUrlHandlerMapping"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "enryold.mcp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpProperties.class)
public class McpAutoConfiguration {
    public McpAutoConfiguration(McpSecurity security) {
        java.util.Objects.requireNonNull(security);
    }

    @Bean
    @ConditionalOnMissingBean
    McpEngine mcpEngine(McpProperties properties, List<McpTool> tools, McpSecurity security, List<McpModule> modules) {
        var identity = JsonMapper.builder().build().createObjectNode()
                .put("name", properties.serverName()).put("version", properties.serverVersion());
        return new McpEngine(identity, tools, security, properties.maxJsonDepth(), properties.maxRequestBytes(), modules);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    McpHttpHandler mcpHttpHandler(McpEngine engine, McpProperties properties, McpSecurity security) {
        return new McpHttpHandler(engine, properties, security);
    }

    @Bean
    @ConditionalOnMissingBean(name = "mcpHandlerMapping")
    SimpleUrlHandlerMapping mcpHandlerMapping(McpHttpHandler handler, McpProperties properties) {
        return new SimpleUrlHandlerMapping(Map.of(properties.path(), handler), -1);
    }
}
