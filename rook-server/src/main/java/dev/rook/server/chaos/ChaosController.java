package dev.rook.server.chaos;

import dev.rook.control.audit.AuditLog;
import dev.rook.control.auth.Principal;
import dev.rook.control.auth.Scope;
import dev.rook.core.error.RookException;
import dev.rook.server.api.ApiAccess;
import dev.rook.server.workflow.WorkerRuntime;
import dev.rook.workflow.outbox.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("chaos")
@RequestMapping("/api/v1/admin/chaos")
class ChaosController {

    record Status(int workersExpected, int workersAlive, boolean relayRunning, long leasedJobs) {}

    private final ApiAccess access;
    private final JdbcClient jdbc;
    private final ObjectProvider<WorkerRuntime> workers;
    private final ObjectProvider<OutboxRelay> relay;
    private final AuditLog audit;
    private final MeterRegistry meters;

    ChaosController(
            ApiAccess access,
            JdbcClient jdbc,
            ObjectProvider<WorkerRuntime> workers,
            ObjectProvider<OutboxRelay> relay,
            AuditLog audit,
            MeterRegistry meters) {
        this.access = access;
        this.jdbc = jdbc;
        this.workers = workers;
        this.relay = relay;
        this.audit = audit;
        this.meters = meters;
    }

    @GetMapping
    Status status(HttpServletRequest request) {
        admin(request);
        return current();
    }

    @PostMapping("/workers/crash")
    Map<String, Object> crash(
            HttpServletRequest request, @RequestParam(defaultValue = "1") int count) {
        Principal principal = admin(request);
        if (count < 1 || count > 64) {
            throw RookException.invalid("count must be between 1 and 64");
        }
        WorkerRuntime runtime = workers.getIfAvailable();
        int crashed = runtime == null ? 0 : runtime.crash(count);
        recorded(principal, "workers_crash", Map.of("requested", count, "crashed", crashed));
        return Map.of("crashed", crashed);
    }

    @PostMapping("/leases/expire")
    Map<String, Object> expireLeases(HttpServletRequest request) {
        Principal principal = admin(request);
        int expired =
                jdbc.sql(
                                "update migration_job set lease_expires_at = now() - interval '1"
                                        + " second' where lease_owner is not null and finished_at"
                                        + " is null")
                        .update();
        recorded(principal, "leases_expire", Map.of("expired", expired));
        return Map.of("expired", expired);
    }

    @PostMapping("/relay/stop")
    Status stopRelay(HttpServletRequest request) {
        Principal principal = admin(request);
        OutboxRelay instance = relay.getIfAvailable();
        if (instance != null) {
            instance.stop();
        }
        recorded(principal, "relay_stop", Map.of());
        return current();
    }

    @PostMapping("/relay/start")
    Status startRelay(HttpServletRequest request) {
        Principal principal = admin(request);
        OutboxRelay instance = relay.getIfAvailable();
        if (instance != null) {
            instance.start();
        }
        recorded(principal, "relay_start", Map.of());
        return current();
    }

    private Principal admin(HttpServletRequest request) {
        Principal principal = access.principal(request, Scope.ADMIN);
        if (!principal.global()) {
            throw RookException.forbidden(
                    "chaos actions need a key that is not tied to one installation");
        }
        return principal;
    }

    private Status current() {
        WorkerRuntime runtime = workers.getIfAvailable();
        OutboxRelay instance = relay.getIfAvailable();
        long leased =
                jdbc.sql(
                                "select count(*) from migration_job where lease_owner is not null"
                                        + " and finished_at is null")
                        .query(Long.class)
                        .single();
        return new Status(
                runtime == null ? 0 : runtime.expected(),
                runtime == null ? 0 : runtime.alive(),
                instance != null && instance.running(),
                leased);
    }

    private void recorded(Principal principal, String action, Map<String, Object> data) {
        meters.counter("rook.chaos.actions", "action", action).increment();
        audit.append("key:" + principal.keyId(), "CHAOS_" + action.toUpperCase(), "chaos", data);
    }
}
