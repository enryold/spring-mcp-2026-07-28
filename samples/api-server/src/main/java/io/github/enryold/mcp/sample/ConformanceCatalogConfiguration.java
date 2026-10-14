package io.github.enryold.mcp.sample;

import io.github.enryold.mcp.McpCatalogModule;
import io.github.enryold.mcp.McpCompletion;
import io.github.enryold.mcp.McpPrompt;
import io.github.enryold.mcp.McpResource;
import io.github.enryold.mcp.McpResourceTemplate;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Resource, prompt, and completion diagnostics required by official conformance. */
@Configuration(proxyBeanMethods = false)
@Profile("conformance")
class ConformanceCatalogConfiguration {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl3fWQAAAAASUVORK5CYII=";

    @Bean
    McpCatalogModule conformanceCatalogModule() {
        return new McpCatalogModule(
                List.of(
                        new McpResource(object("""
                                {"uri":"test://static-binary","name":"static-binary","title":"Static Binary Resource",
                                 "description":"A static binary resource (image) for testing","mimeType":"image/png"}
                                """), (uri, context) -> object("""
                                {"contents":[{"uri":"test://static-binary","mimeType":"image/png","blob":"%s"}]}
                                """.formatted(PNG_1X1))),
                        new McpResource(object("""
                                {"uri":"test://static-text","name":"static-text","title":"Static Text Resource",
                                 "description":"A static text resource for testing","mimeType":"text/plain"}
                                """), (uri, context) -> object("""
                                {"contents":[{"uri":"test://static-text","mimeType":"text/plain",
                                 "text":"This is the content of the static text resource."}]}
                                """))),
                List.of(new McpResourceTemplate(object("""
                                {"uriTemplate":"test://template/{id}/data","name":"template","title":"Resource Template",
                                 "description":"A resource template with parameter substitution","mimeType":"application/json"}
                                """), ConformanceCatalogConfiguration::matchesTemplate,
                        (uri, context) -> templateResult(uri))),
                List.of(
                        new McpPrompt(object("""
                                {"name":"test_input_required_result_prompt","title":"Input Required Result Prompt",
                                 "description":"A prompt that requests user context"}
                                """), (arguments, context) -> inputRequiredPrompt(context.requestParams())),
                        new McpPrompt(object("""
                                {"name":"test_prompt_with_arguments","title":"Prompt With Arguments",
                                 "description":"A prompt with required arguments","arguments":[
                                   {"name":"arg1","description":"First test argument","required":true},
                                   {"name":"arg2","description":"Second test argument","required":true}]}
                                """), (arguments, context) -> textPrompt(
                                "Prompt with arguments: arg1='" + arguments.path("arg1").asText()
                                        + "', arg2='" + arguments.path("arg2").asText() + "'")),
                        new McpPrompt(object("""
                                {"name":"test_prompt_with_embedded_resource","title":"Prompt With Embedded Resource",
                                 "description":"A prompt that includes an embedded resource","arguments":[
                                   {"name":"resourceUri","description":"URI of the resource to embed","required":true}]}
                                """), (arguments, context) -> embeddedResourcePrompt(arguments.path("resourceUri").asText())),
                        new McpPrompt(object("""
                                {"name":"test_prompt_with_image","title":"Prompt With Image",
                                 "description":"A prompt that includes image content"}
                                """), (arguments, context) -> imagePrompt()),
                        new McpPrompt(object("""
                                {"name":"test_simple_prompt","title":"Simple Test Prompt",
                                 "description":"A simple prompt without arguments"}
                                """), (arguments, context) -> textPrompt("This is a simple prompt for testing."))),
                List.of(
                        new McpCompletion(object("""
                                {"type":"ref/prompt","name":"test_prompt_with_arguments"}
                                """), "arg1", (value, previous, context) -> completion(
                                List.of("testValue1", "testValue2").stream().filter(candidate -> candidate.startsWith(value)).toList())),
                        new McpCompletion(object("""
                                {"type":"ref/resource","uri":"test://template/{id}/data"}
                                """), "id", (value, previous, context) -> completion(
                                List.of("123", "456").stream().filter(candidate -> candidate.startsWith(value)).toList()))));
    }

    private static boolean matchesTemplate(String uri) {
        return uri.startsWith("test://template/") && uri.endsWith("/data")
                && uri.length() > "test://template//data".length();
    }

    private static ObjectNode templateResult(String uri) {
        String id = uri.substring("test://template/".length(), uri.length() - "/data".length());
        ObjectNode value = JSON.createObjectNode().put("id", id).put("templateTest", true)
                .put("data", "Data for ID: " + id);
        ObjectNode result = JSON.createObjectNode();
        result.putArray("contents").add(JSON.createObjectNode().put("uri", uri)
                .put("mimeType", "application/json").put("text", value.toString()));
        return result;
    }

    private static ObjectNode textPrompt(String text) {
        ObjectNode result = JSON.createObjectNode();
        result.putArray("messages").add(message(JSON.createObjectNode().put("type", "text").put("text", text)));
        return result;
    }

    private static ObjectNode inputRequiredPrompt(ObjectNode params) {
        var response = params.at("/inputResponses/user_context");
        if ("accept".equals(response.path("action").asText()) && response.at("/content/context").isTextual()) {
            return textPrompt("Prompt context: " + response.at("/content/context").asText());
        }
        ObjectNode result = JSON.createObjectNode().put("resultType", "input_required");
        ObjectNode request = result.putObject("inputRequests").putObject("user_context")
                .put("method", "elicitation/create");
        ObjectNode requestParams = request.putObject("params")
                .put("message", "What context should the prompt use?");
        ObjectNode schema = requestParams.putObject("requestedSchema").put("type", "object");
        schema.putObject("properties").putObject("context").put("type", "string");
        schema.putArray("required").add("context");
        return result;
    }

    private static ObjectNode embeddedResourcePrompt(String uri) {
        ObjectNode embedded = JSON.createObjectNode().put("type", "resource");
        embedded.putObject("resource").put("uri", uri).put("mimeType", "text/plain")
                .put("text", "Embedded resource content for testing.");
        ObjectNode result = JSON.createObjectNode();
        result.putArray("messages").add(message(embedded)).add(message(
                JSON.createObjectNode().put("type", "text").put("text", "Please process the embedded resource above.")));
        return result;
    }

    private static ObjectNode imagePrompt() {
        ObjectNode result = JSON.createObjectNode();
        result.putArray("messages")
                .add(message(JSON.createObjectNode().put("type", "image").put("data", PNG_1X1).put("mimeType", "image/png")))
                .add(message(JSON.createObjectNode().put("type", "text").put("text", "Please analyze the image above.")));
        return result;
    }

    private static ObjectNode message(ObjectNode content) {
        return JSON.createObjectNode().put("role", "user").set("content", content);
    }

    private static ObjectNode completion(List<String> values) {
        ObjectNode completion = JSON.createObjectNode().put("total", values.size()).put("hasMore", false);
        var array = completion.putArray("values");
        values.forEach(array::add);
        return completion;
    }

    private static ObjectNode object(String json) {
        try {
            return (ObjectNode) JSON.readTree(json);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid conformance catalog fixture", invalid);
        }
    }
}
