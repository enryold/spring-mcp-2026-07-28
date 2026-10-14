package io.github.enryold.mcp.sample;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EchoController {
    private final EchoService service;
    private final String resetKey;

    public EchoController(EchoService service, @Value("${fixture.reset-key:local-reset-only}") String resetKey) {
        this.service = service;
        this.resetKey = resetKey;
    }

    @GetMapping("/api/echo")
    public Map<String, String> echo(@RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam String text) {
        return service.echo(service.authenticate(authorization), text);
    }

    @GetMapping("/api/items/{id}")
    public EchoService.Item read(@RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable String id) {
        return service.read(service.authenticate(authorization), id);
    }

    @PostMapping("/api/items")
    public ResponseEntity<EchoService.Item> create(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody EchoService.CreateItem request) {
        return ResponseEntity.status(201).body(service.create(service.authenticate(authorization), request));
    }

    @PostMapping("/api/items/slow")
    public ResponseEntity<EchoService.Item> createThenDelay(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody EchoService.SlowCreateItem request) {
        return ResponseEntity.status(201).body(service.createThenDelay(service.authenticate(authorization), request));
    }

    @PostMapping("/api/error")
    public void fail(@RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody EchoService.FailureRequest request) {
        service.fail(service.authenticate(authorization), request);
    }

    @PostMapping("/api/slow")
    public EchoService.DelayResult slow(@RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody EchoService.DelayRequest request) {
        return service.slow(service.authenticate(authorization), request);
    }

    @GetMapping("/api/test/state")
    public Map<String, Object> state(@RequestHeader(name = "Authorization", required = false) String authorization) {
        return service.state(service.authenticate(authorization));
    }

    @PostMapping("/api/test/reset")
    public ResponseEntity<Void> reset(@RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestHeader(name = "X-Fixture-Reset-Key", required = false) String suppliedKey,
            HttpServletRequest request) throws Exception {
        var identity = service.authenticate(authorization);
        if (!identity.scopes().contains("write") || !resetKey.equals(suppliedKey)
                || !InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
            throw new EchoService.FixtureFailure(403, "reset_denied", "Fixture reset is denied");
        }
        service.reset();
        return ResponseEntity.noContent().build();
    }
}
