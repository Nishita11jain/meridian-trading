package com.trading.api.extensions.strategies;

import com.trading.api.JwtTestTokens;
import com.trading.api.config.SecurityConfig;
import com.trading.api.exception.GlobalExceptionHandler;
import com.trading.domain.exception.AccountNotActiveException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The strategies routes through the real security filter chain: a missing or forged token is
 * refused before the controller, a token for another account is ACC-403, an unknown strategy is
 * STRAT-404, and the cap is LIMIT-429. The service is mocked; this is about the wire contract and
 * the authorisation boundary, not the evaluation/firing logic.
 */
@WebMvcTest(
        controllers = StrategiesController.class,
        properties = "app.security.jwt-secret=01234567890123456789012345678901"
)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class StrategiesControllerWebMvcTest {

    private static final String JWT_SECRET = "01234567890123456789012345678901";
    private static final long ACCOUNT = 4L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StrategiesService strategiesService;

    private String bearer(long accountId) throws Exception {
        return "Bearer " + JwtTestTokens.validTokenFor(accountId, JWT_SECRET);
    }

    private static StrategyResponse sample() {
        return new StrategyResponse(1L, "NVDA", "BUY", "CROSSOVER", 6, 12, null, null,
                20, new BigDecimal("5000"), 100, 3, 0, "ACTIVE", null, Instant.parse("2026-10-07T00:00:00Z"));
    }

    @Test
    void missingTokenIsRefusedBeforeTheController() throws Exception {
        mockMvc.perform(get("/api/v1/strategies/4"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
        verify(strategiesService, never()).list(any(), any());
    }

    @Test
    void forgedSignatureIsRefused() throws Exception {
        String forged = JwtTestTokens.validTokenFor(ACCOUNT, "the-wrong-secret-the-wrong-secret!!");
        mockMvc.perform(get("/api/v1/strategies/4").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    void aValidTokenReachesTheServiceAndReturnsStrategies() throws Exception {
        when(strategiesService.list(eq(4L), eq(ACCOUNT))).thenReturn(java.util.List.of(sample()));

        mockMvc.perform(get("/api/v1/strategies/4").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("NVDA"))
                .andExpect(jsonPath("$[0].side").value("BUY"));
    }

    @Test
    void aTokenForAnotherAccountIsForbidden() throws Exception {
        when(strategiesService.list(eq(4L), eq(99L))).thenThrow(new AccountNotActiveException("account is not active"));

        mockMvc.perform(get("/api/v1/strategies/4").header("Authorization", bearer(99L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    @Test
    void creatingOverTheCapIsTooManyRequests() throws Exception {
        when(strategiesService.create(eq(4L), eq(ACCOUNT), any()))
                .thenThrow(new StrategyLimitException("cap reached"));

        mockMvc.perform(post("/api/v1/strategies/4")
                        .header("Authorization", bearer(ACCOUNT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"NVDA","side":"BUY","strategyType":"CROSSOVER","fastWindow":6,"slowWindow":12,
                                 "orderQuantity":20,"maxSpend":5000,"maxPositionSize":100,"maxConsecutiveFailures":3}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("LIMIT-429"));
    }

    @Test
    void updatingStatusOnAnUnknownStrategyIsNotFound() throws Exception {
        when(strategiesService.updateStatus(eq(4L), eq(ACCOUNT), eq(99L), any()))
                .thenThrow(new StrategyNotFoundException("no such strategy"));

        mockMvc.perform(put("/api/v1/strategies/4/99/status")
                        .header("Authorization", bearer(ACCOUNT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STRAT-404"));
    }

    @Test
    void theLogRouteReturnsEntries() throws Exception {
        when(strategiesService.log(eq(4L), eq(ACCOUNT), eq(1L), anyInt()))
                .thenReturn(java.util.List.of(new StrategyExecutionResponse(
                        Instant.parse("2026-10-07T09:00:00Z"), new BigDecimal("300"), "BUY 20", "PLACED", "ok", null)));

        mockMvc.perform(get("/api/v1/strategies/4/1/log").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("PLACED"));
    }

    @Test
    void healthRequiresATokenLikeEveryOtherRoute() throws Exception {
        mockMvc.perform(get("/api/v1/strategies/health"))
                .andExpect(status().isUnauthorized());
    }
}
