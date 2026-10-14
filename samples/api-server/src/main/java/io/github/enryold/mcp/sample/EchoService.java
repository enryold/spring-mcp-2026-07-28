package io.github.enryold.mcp.sample;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class EchoService {
    private static final Instant FIXED_TIME = Instant.parse("2026-10-14T00:00:00Z");
    private static final Identity ALICE_READ = new Identity("alice", "red", Set.of("read"));
    private static final Identity ALICE_WRITE = new Identity("alice", "red", Set.of("read", "write"));
    private static final Identity BOB_WRITE = new Identity("bob", "blue", Set.of("read", "write"));
    private static final Identity RATE_LIMITED = new Identity("rate-limited", "red", Set.of("read"));
    private static final Map<String, Identity> MCP_IDENTITIES = Map.of(
            "alice-read", ALICE_READ,
            "alice-write", ALICE_WRITE,
            "bob-write", BOB_WRITE,
            "alice-rate-limited", RATE_LIMITED);
    private static final Map<String, Identity> API_IDENTITIES = Map.of(
            "api-alice-read", ALICE_READ,
            "api-alice-write", ALICE_WRITE,
            "api-bob-write", BOB_WRITE);
    private static final Map<Identity, String> API_CREDENTIALS = Map.of(
            ALICE_READ, "api-alice-read",
            ALICE_WRITE, "api-alice-write",
            BOB_WRITE, "api-bob-write");

    private final Map<String, Map<String, Item>> stores = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AtomicInteger>> counts = new ConcurrentHashMap<>();

    public EchoService() {
        reset();
    }

    public Identity authenticate(String authorization) {
        return authenticate(authorization, API_IDENTITIES);
    }

    public Identity authenticateMcp(String authorization) {
        return authenticate(authorization, MCP_IDENTITIES);
    }

    public Identity trustedMcpIdentity(String subject, String tenantId, Set<String> scopes) {
        Identity candidate = new Identity(subject, tenantId, scopes);
        return MCP_IDENTITIES.containsValue(candidate) ? candidate : rejectedIdentity();
    }

    public String apiAuthorization(String subject, String tenantId, Set<String> scopes) {
        String credential = API_CREDENTIALS.get(trustedMcpIdentity(subject, tenantId, scopes));
        if (credential == null) return rejectedIdentity();
        return "Bearer " + credential;
    }

    private Identity authenticate(String authorization, Map<String, Identity> identities) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return rejectedIdentity();
        }
        var identity = identities.get(authorization.substring("Bearer ".length()));
        if (identity == null) {
            return rejectedIdentity();
        }
        return identity;
    }

    private static <T> T rejectedIdentity() {
        throw new FixtureFailure(401, "invalid_token", "A valid fixture bearer token is required");
    }

    public Map<String, String> echo(Identity identity, String text) {
        require(identity, "read");
        count(identity.tenantId(), "echo");
        return Map.of("text", text);
    }

    public Item read(Identity identity, String id) {
        require(identity, "read");
        count(identity.tenantId(), "read");
        Item item = stores.get(identity.tenantId()).get(id);
        if (item == null) {
            throw new FixtureFailure(404, "item_not_found", "Fixture item was not found");
        }
        return item;
    }

    public Item create(Identity identity, CreateItem request) {
        require(identity, "write");
        if (request == null || request.id() == null || request.id().isBlank() || request.value() == null) {
            throw new FixtureFailure(400, "invalid_input", "id and value are required");
        }
        var item = new Item(request.id(), identity.tenantId(), request.value(), FIXED_TIME.toString());
        if (stores.get(identity.tenantId()).putIfAbsent(request.id(), item) != null) {
            throw new FixtureFailure(409, "item_exists", "Fixture item already exists");
        }
        count(identity.tenantId(), "create");
        return item;
    }

    public Item createThenDelay(Identity identity, SlowCreateItem request) {
        if (request == null || request.delayMs() < 0 || request.delayMs() > 2_000) {
            throw new FixtureFailure(400, "invalid_input", "delayMs must be between 0 and 2000");
        }
        Item committed = create(identity, new CreateItem(request.id(), request.value()));
        delay(request.delayMs());
        return committed;
    }

    public void fail(Identity identity, FailureRequest request) {
        require(identity, "read");
        count(identity.tenantId(), "error");
        if (request == null || request.kind() == null) {
            throw new FixtureFailure(400, "invalid_input", "kind is required");
        }
        if ("expected".equals(request.kind())) {
            throw new FixtureFailure(409, "fixture_conflict", "Expected fixture conflict");
        }
        if ("unexpected".equals(request.kind())) {
            throw new IllegalStateException("fixture exploded with internal-only detail");
        }
        throw new FixtureFailure(400, "invalid_input", "kind must be expected or unexpected");
    }

    public DelayResult slow(Identity identity, DelayRequest request) {
        require(identity, "read");
        if (request == null || request.delayMs() < 0 || request.delayMs() > 2_000) {
            throw new FixtureFailure(400, "invalid_input", "delayMs must be between 0 and 2000");
        }
        count(identity.tenantId(), "slow");
        delay(request.delayMs());
        return new DelayResult(request.delayMs());
    }

    private void delay(int delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FixtureFailure(500, "fixture_interrupted", "Fixture operation was interrupted");
        }
    }

    public Map<String, Object> state(Identity identity) {
        require(identity, "read");
        var sortedCounts = new LinkedHashMap<String, Integer>();
        counts.get(identity.tenantId()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sortedCounts.put(entry.getKey(), entry.getValue().get()));
        var items = new ArrayList<>(stores.get(identity.tenantId()).values());
        items.sort(Comparator.comparing(Item::id));
        return Map.of("tenantId", identity.tenantId(), "counts", sortedCounts, "items", items);
    }

    public synchronized void reset() {
        stores.clear();
        counts.clear();
        for (String tenant : Set.of("red", "blue")) {
            var items = new ConcurrentHashMap<String, Item>();
            items.put("seed", new Item("seed", tenant, "seeded", FIXED_TIME.toString()));
            stores.put(tenant, items);
            var initialCounts = new ConcurrentHashMap<String, AtomicInteger>();
            for (String operation : Set.of("echo", "read", "create", "error", "slow")) {
                initialCounts.put(operation, new AtomicInteger());
            }
            counts.put(tenant, initialCounts);
        }
    }

    private void require(Identity identity, String scope) {
        if (!identity.scopes().contains(scope)) {
            throw new FixtureFailure(403, "insufficient_scope", "Fixture identity lacks required scope");
        }
    }

    private void count(String tenant, String operation) {
        counts.get(tenant).computeIfAbsent(operation, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public record Identity(String subject, String tenantId, Set<String> scopes) {
        public Identity {
            scopes = Set.copyOf(scopes);
        }
    }

    public record Item(String id, String tenantId, String value, String createdAt) {}

    public record CreateItem(String id, String value) {}

    public record SlowCreateItem(String id, String value, int delayMs) {}

    public record FailureRequest(String kind) {}

    public record DelayRequest(int delayMs) {}

    public record DelayResult(int delayMs) {}

    public static final class FixtureFailure extends RuntimeException {
        private final int status;
        private final String code;

        FixtureFailure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        int status() {
            return status;
        }

        String code() {
            return code;
        }
    }
}
