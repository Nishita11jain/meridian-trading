package com.trading.api.extensions.portfolio;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Liveness for the portfolio module at /health, which portfolio-api.yaml declares public. It answers 200 as long
 * as the service runs; a failing dependency lowers {@code status} to "degraded" instead of failing the response.
 *
 * <p>Postgres is checked with a trivial query. Fauxnance is judged by how recent the latest stored quote is, so
 * this route never spends quota calling it.
 */
@RestController
public class PortfolioHealthController {

    /** The poller runs every five minutes; a quote older than this means it, or Fauxnance, has stopped. */
    static final Duration QUOTE_MAX_AGE = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;

    public PortfolioHealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        String postgres = postgresStatus();
        String fauxnance = "ok".equals(postgres) ? fauxnanceStatus() : "down";
        List<Dependency> dependencies = new ArrayList<>();
        dependencies.add(new Dependency("postgres", postgres, null));
        dependencies.add(new Dependency("fauxnance", fauxnance, null));
        boolean allOk = dependencies.stream().allMatch(d -> "ok".equals(d.status()));
        return ResponseEntity.ok(new HealthResponse(allOk ? "ok" : "degraded", dependencies, Instant.now()));
    }

    private String postgresStatus() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return "ok";
        } catch (RuntimeException unreachable) {
            return "down";
        }
    }

    private String fauxnanceStatus() {
        try {
            java.sql.Timestamp latest = jdbc.queryForObject("SELECT MAX(updated_on) FROM market_price", java.sql.Timestamp.class);
            if (latest == null) {
                return "degraded";
            }
            return Duration.between(latest.toInstant(), Instant.now()).compareTo(QUOTE_MAX_AGE) <= 0 ? "ok" : "degraded";
        } catch (RuntimeException unreadable) {
            return "degraded";
        }
    }

    record HealthResponse(String status, List<Dependency> dependencies, Instant asOf) {
    }

    record Dependency(String name, String status, Integer quotaRemaining) {
    }
}
