package io.github.enryold.mcp.spring;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.github.enryold.mcp.McpContext;
import io.github.enryold.mcp.McpEngine;
import io.github.enryold.mcp.McpProtocolException;
import io.github.enryold.mcp.McpRequest;
import org.springframework.http.MediaType;
import org.springframework.web.HttpRequestHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Stateless Streamable HTTP binding with bounded request and notification queues. */
public final class McpHttpHandler implements HttpRequestHandler, AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final BigDecimal MAX_SAFE_INTEGER = new BigDecimal("9007199254740991");
    private final McpEngine engine;
    private final McpProperties properties;
    private final McpSecurity security;
    private final ThreadPoolExecutor executor;

    public McpHttpHandler(McpEngine engine, McpProperties properties, McpSecurity security) {
        this.engine = engine;
        this.properties = properties;
        this.security = security;
        executor = new ThreadPoolExecutor(0, properties.maxConcurrentRequests(), 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofVirtual().name("mcp-", 0).factory());
    }

    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Vary", "Authorization, Origin");
        var cancelled = new AtomicBoolean();
        Instant deadline = Instant.now().plus(properties.requestTimeout());
        McpRequest message = null;
        Future<JsonNode> pending = null;
        try {
            validateOrigin(request);
            if (!"POST".equals(request.getMethod())) {
                response.setHeader("Allow", "POST");
                throw failure(405, -32000, "Method not allowed", null);
            }
            validateMediaTypes(request);
            McpContext context = security.authenticate(request, deadline, cancelled::get);
            if (context == null || context.subject() == null || context.subject().isBlank())
                throw failure(401, -32001, "Authentication required", null);
            long declaredLength = request.getContentLengthLong();
            if (declaredLength > properties.maxRequestBytes())
                throw failure(413, -32000, "Request body too large", null);
            byte[] body = request.getInputStream().readNBytes(properties.maxRequestBytes() + 1);
            if (body.length > properties.maxRequestBytes())
                throw failure(413, -32000, "Request body too large", null);
            message = engine.readRequest(body);
            validateHeaders(request, message);
            McpRequest validated = message;
            boolean streaming = engine.streams(validated);
            BlockingQueue<ObjectNode> notifications = streaming ? new ArrayBlockingQueue<>(256) : null;
            McpContext executionContext = streaming
                    ? context.withRequest(validated, notification -> {
                        if (!notifications.offer(notification)) throw new IllegalStateException("notification queue is full");
                    })
                    : context;
            try {
                pending = executor.submit(() -> engine.dispatch(validated, executionContext, security));
            } catch (RejectedExecutionException rejected) {
                throw failure(429, -32000, "Too many requests", message.id());
            }
            if (streaming) {
                stream(response, pending, notifications, cancelled, deadline, message.id());
                pending = null;
                return;
            }
            long remaining = java.time.Duration.between(Instant.now(), deadline).toNanos();
            if (remaining <= 0) throw new TimeoutException();
            JsonNode result = pending.get(remaining, TimeUnit.NANOSECONDS);
            write(response, 200, result);
        } catch (McpProtocolException rejected) {
            writeError(request, response, rejected);
        } catch (TimeoutException timeout) {
            cancelled.set(true);
            if (pending != null) pending.cancel(true);
            writeError(request, response, failure(408, -32000, "Request deadline exceeded", message == null ? null : message.id()));
        } catch (InterruptedException interrupted) {
            cancelled.set(true);
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            writeError(request, response, failure(408, -32000, "Request cancelled", message == null ? null : message.id()));
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof McpProtocolException rejected) writeError(request, response, rejected);
            else writeError(request, response, failure(500, -32603, "Internal error", message == null ? null : message.id()));
        } catch (RuntimeException failed) {
            writeError(request, response, failure(500, -32603, "Internal error", message == null ? null : message.id()));
        } finally {
            cancelled.set(true);
        }
    }

    private static void stream(HttpServletResponse response, Future<JsonNode> pending,
            BlockingQueue<ObjectNode> notifications, AtomicBoolean cancelled, Instant deadline, JsonNode id)
            throws IOException, TimeoutException, InterruptedException, ExecutionException {
        jakarta.servlet.ServletOutputStream output = null;
        long keepAliveAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try {
            while (!pending.isDone()) {
                long remaining = java.time.Duration.between(Instant.now(), deadline).toNanos();
                if (remaining <= 0) throw new TimeoutException();
                ObjectNode notification = notifications.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)),
                        TimeUnit.NANOSECONDS);
                if (notification != null) {
                    if (output == null) output = startSse(response);
                    writeSse(output, notification);
                }
                if (output != null && System.nanoTime() >= keepAliveAt) {
                    output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    keepAliveAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                }
            }
            ObjectNode notification;
            while ((notification = notifications.poll()) != null) {
                if (output == null) output = startSse(response);
                writeSse(output, notification);
            }
            JsonNode result = pending.get();
            if (output == null) output = startSse(response);
            writeSse(output, result);
        } catch (TimeoutException timeout) {
            cancelled.set(true);
            pending.cancel(true);
            if (output == null) throw timeout;
            writeSse(output, failure(408, -32000, "Request deadline exceeded", id).toResponse());
        } catch (InterruptedException interrupted) {
            cancelled.set(true);
            pending.cancel(true);
            Thread.currentThread().interrupt();
            if (output == null) throw interrupted;
            writeSse(output, failure(408, -32000, "Request cancelled", id).toResponse());
        } catch (ExecutionException failed) {
            if (output == null) throw failed;
            McpProtocolException error = failed.getCause() instanceof McpProtocolException rejected
                    ? rejected : failure(500, -32603, "Internal error", id);
            writeSse(output, error.toResponse());
        } finally {
            if (!pending.isDone()) {
                cancelled.set(true);
                pending.cancel(true);
            }
        }
    }

    private static jakarta.servlet.ServletOutputStream startSse(HttpServletResponse response) throws IOException {
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "private, no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return response.getOutputStream();
    }

    private static void writeSse(jakarta.servlet.ServletOutputStream output, JsonNode message) throws IOException {
        output.write("event: message\ndata: ".getBytes(StandardCharsets.UTF_8));
        output.write(JSON.writeValueAsBytes(message));
        output.write("\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void validateOrigin(HttpServletRequest request) {
        String origin = singleHeader(request, "Origin", 403, null);
        if (origin != null && !properties.allowedOrigins().contains(origin))
            throw failure(403, -32000, "Origin is not allowed", null);
    }

    private void validateMediaTypes(HttpServletRequest request) {
        try {
            MediaType content = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType());
            if (!content.getType().equals("application") || !content.getSubtype().equals("json")
                    || content.getCharset() != null && !content.getCharset().equals(StandardCharsets.UTF_8))
                throw failure(415, -32000, "Content-Type must be application/json with UTF-8 encoding", null);
        } catch (IllegalArgumentException invalid) {
            throw failure(415, -32000, "Content-Type must be application/json with UTF-8 encoding", null);
        }
        try {
            List<MediaType> accept = MediaType.parseMediaTypes(java.util.Collections.list(request.getHeaders("Accept")));
            if (!accepts(accept, MediaType.APPLICATION_JSON) || !accepts(accept, MediaType.TEXT_EVENT_STREAM))
                throw failure(406, -32000, "Accept must include application/json and text/event-stream", null);
        } catch (IllegalArgumentException invalid) {
            throw failure(406, -32000, "Accept must include application/json and text/event-stream", null);
        }
    }

    private static boolean accepts(List<MediaType> accepted, MediaType required) {
        return accepted.stream().anyMatch(type -> type.getQualityValue() > 0
                && type.getType().equals(required.getType()) && type.getSubtype().equals(required.getSubtype()));
    }

    private void validateHeaders(HttpServletRequest request, McpRequest message) {
        JsonNode id = message.id();
        String version = singleHeader(request, "MCP-Protocol-Version", 400, id);
        String method = singleHeader(request, "Mcp-Method", 400, id);
        if (version == null || !version.equals(message.metadata().path("io.modelcontextprotocol/protocolVersion").asString())
                || method == null || !method.equals(message.method()) || !plainAscii(method))
            throw failure(400, -32020, "Required HTTP headers do not match request metadata", id);
        String nameField = switch (message.method()) {
            case "tools/call", "prompts/get" -> "name";
            case "resources/read" -> "uri";
            case "tasks/get", "tasks/update", "tasks/cancel" ->
                    engine.supports(message.method()) ? "taskId" : null;
            default -> null;
        };
        if (nameField != null) {
            String name = singleHeader(request, "Mcp-Name", 400, id);
            if (name == null || !message.params().path(nameField).isString()
                    || !decode(name, id).equals(message.params().path(nameField).asString()))
                throw failure(400, -32020, "Mcp-Name does not match the request", id);
        }
        if ("tools/call".equals(message.method())) {
            JsonNode descriptor = engine.descriptor(message.params().path("name").asString());
            if (descriptor != null) validateParameterHeaders(request, descriptor.path("inputSchema"),
                    message.params().path("arguments"), id);
        }
    }

    private void validateParameterHeaders(HttpServletRequest request, JsonNode schema, JsonNode arguments, JsonNode id) {
        for (var property : schema.path("properties").properties()) {
            JsonNode declaration = property.getValue();
            JsonNode value = arguments.path(property.getKey());
            if (declaration.has("x-mcp-header")) {
                String header = singleHeader(request, "Mcp-Param-" + declaration.path("x-mcp-header").asString(), 400, id);
                if (value.isMissingNode() || value.isNull()) {
                    if (header != null) throw failure(400, -32020, "Unexpected mirrored parameter header", id);
                } else {
                    if (header == null) throw failure(400, -32020, "Missing mirrored parameter header", id);
                    String decoded = decode(header, id);
                    boolean equal = decoded.equals(value.isString() ? value.asString() : value.toString());
                    if ("integer".equals(declaration.path("type").asString()) && value.isNumber()) {
                        try {
                            var number = value.decimalValue();
                            equal = number.abs().compareTo(MAX_SAFE_INTEGER) <= 0
                                    && number.stripTrailingZeros().scale() <= 0
                                    && number.compareTo(new BigDecimal(decoded)) == 0;
                        } catch (NumberFormatException invalid) { equal = false; }
                    }
                    if (!equal) throw failure(400, -32020, "Mirrored parameter header does not match the request", id);
                }
            }
            validateParameterHeaders(request, declaration, value, id);
        }
    }

    private static String singleHeader(HttpServletRequest request, String name, int status, JsonNode id) {
        var values = java.util.Collections.list(request.getHeaders(name));
        if (values.size() > 1) throw failure(status, status == 403 ? -32001 : -32020, "Duplicate HTTP header", id);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static String decode(String value, JsonNode id) {
        if (!plainAscii(value)) throw failure(400, -32020, "Invalid mirrored header encoding", id);
        if (value.startsWith("=?base64?") && value.endsWith("?=")) {
            try {
                String encoded = value.substring(9, value.length() - 2);
                byte[] bytes = Base64.getDecoder().decode(encoded);
                if (!Base64.getEncoder().encodeToString(bytes).equals(encoded))
                    throw new IllegalArgumentException();
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (Exception invalid) {
                throw failure(400, -32020, "Invalid mirrored header encoding", id);
            }
        }
        return value;
    }

    private static boolean plainAscii(String value) {
        return value.equals(value.strip())
                && value.chars().allMatch(character -> character >= 0x20 && character <= 0x7e);
    }

    private static McpProtocolException failure(int status, int code, String message, JsonNode id) {
        var data = code == -32603 ? JSON.createObjectNode().put("correlationId", java.util.UUID.randomUUID().toString()) : null;
        return new McpProtocolException(status, code, message, data, id);
    }
    private void writeError(HttpServletRequest request, HttpServletResponse response, McpProtocolException failure) throws IOException {
        String challenge = security.wwwAuthenticate(request, failure);
        if (challenge != null) response.setHeader("WWW-Authenticate", challenge);
        if (failure.httpStatus() == 429) response.setHeader("Retry-After",
                failure.data() != null && failure.data().path("retryAfterSeconds").isInt()
                        ? Integer.toString(failure.data().path("retryAfterSeconds").asInt()) : "1");
        write(response, failure.httpStatus(), failure.toResponse());
    }
    private static void write(HttpServletResponse response, int status, JsonNode body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(JSON.writeValueAsBytes(body));
    }
    @Override public void close() { executor.shutdownNow(); }
}
