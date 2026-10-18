package com.trading.api;

import com.trading.api.dto.AccountResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 6's explicit "Integration Test" requirement: exercises the real
 * controller, service and MyBatis persistence together against a real
 * Postgres connection -- nothing here is mocked, unlike every other test
 * in this project. Requires a real Postgres instance, reachable using the
 * same POSTGRES_* environment variables the application itself uses, with
 * Sprint 3's schema and seed data already applied (see databases/postgresql/apply.sh).
 * Account id=1 / account_id="ETP-4101" is the real seeded account this
 * test reads.
 * 
 * These tests are DISABLED by default because they require a real database.
 * Enable them only when running against a live PostgreSQL instance with seed data.
 */
@SpringBootTest(properties = "app.security.jwt-secret=01234567890123456789012345678901")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Disabled("Integration tests require real PostgreSQL database with seed data. Enable only when database is available.")
class AccountReadFlowIntegrationTest {

    private static final String JWT_SECRET = "01234567890123456789012345678901";

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void ensureSecretIsDocumented() {
        // The secret is supplied through @SpringBootTest(properties=...),
        // and the signed bearer token below must use the same value.
    }

    @Test
    void readingARealSeededAccountFlowsThroughTheWholeRealStack() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1").header("Authorization", bearerToken(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.accountId").value("ETP-4101"));
    }

    @Test
    void anUnknownAccountReturns404ThroughTheWholeRealStack() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/999999").header("Authorization", bearerToken(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACC-404"));
    }

    @Test
    void positionsForARealSeededAccountFlowThroughTheWholeRealStack() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1/positions").header("Authorization", bearerToken(1L)))
                .andExpect(status().isOk());
        // Not asserting specific holdings here -- seed data content can
        // change independently of this test's purpose, which is proving
        // the real controller-service-mapper-Postgres path works end to
        // end, not re-verifying exact seed values (already covered
        // elsewhere by AccountServiceImplTest's mocked unit tests).
    }

    private static String bearerToken(long accountId) throws Exception {
        return "Bearer " + JwtTestTokens.validTokenFor(accountId, JWT_SECRET);
    }
}