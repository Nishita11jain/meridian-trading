package com.trading.api.extensions.advice;

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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The advice routes through the real security filter chain: a missing or forged token is refused
 * before the controller, a valid token reaches it, a token for another account is refused ACC-403,
 * a symbol outside the account's holdings/watchlists is ADV-404, and an unavailable refresh with
 * nothing cached is ADV-503.
 */
@WebMvcTest(
        controllers = AdviceController.class,
        properties = "app.security.jwt-secret=01234567890123456789012345678901"
)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdviceControllerWebMvcTest {

    private static final String JWT_SECRET = "01234567890123456789012345678901";
    private static final long ACCOUNT = 4L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdviceService adviceService;

    private String bearer(long accountId) throws Exception {
        return "Bearer " + JwtTestTokens.validTokenFor(accountId, JWT_SECRET);
    }

    private static SignalResponse sample() {
        return new SignalResponse("AAA", "BUY", new BigDecimal("0.5"), "test rationale", "test summary",
                Instant.parse("2026-10-07T00:00:00Z"), Instant.parse("2026-10-08T00:00:00Z"), false, null, SignalResponse.DISCLAIMER);
    }

    @Test
    void missingTokenIsRefusedBeforeTheController() throws Exception {
        mockMvc.perform(get("/api/v1/advice/4/signals"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
        verify(adviceService, never()).listSignals(any(), any());
    }

    @Test
    void forgedSignatureIsRefused() throws Exception {
        String forged = JwtTestTokens.validTokenFor(ACCOUNT, "the-wrong-secret-the-wrong-secret!!");
        mockMvc.perform(get("/api/v1/advice/4/signals").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    void aValidTokenReachesTheServiceAndReturnsSignals() throws Exception {
        when(adviceService.listSignals(eq(4L), eq(ACCOUNT))).thenReturn(java.util.List.of(sample()));

        mockMvc.perform(get("/api/v1/advice/4/signals").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAA"))
                .andExpect(jsonPath("$[0].direction").value("BUY"));
    }

    @Test
    void aTokenForAnotherAccountIsForbidden() throws Exception {
        when(adviceService.listSignals(eq(4L), eq(99L)))
                .thenThrow(new AccountNotActiveException("account is not active"));

        mockMvc.perform(get("/api/v1/advice/4/signals").header("Authorization", bearer(99L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    @Test
    void analyseOnAnIneligibleSymbolIsNotFound() throws Exception {
        when(adviceService.analyse(eq(4L), eq(ACCOUNT), eq("ZZZ")))
                .thenThrow(new AdviceNotEligibleException("not eligible"));

        mockMvc.perform(post("/api/v1/advice/4/signals/ZZZ/analyse").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ADV-404"));
    }

    @Test
    void analyseWithNothingCachedAndFauxnanceDownIsServiceUnavailable() throws Exception {
        when(adviceService.analyse(eq(4L), eq(ACCOUNT), eq("AAA")))
                .thenThrow(new AdviceUnavailableException("unavailable"));

        mockMvc.perform(post("/api/v1/advice/4/signals/AAA/analyse").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("ADV-503"));
    }

    @Test
    void healthRequiresATokenLikeEveryOtherRoute() throws Exception {
        // This service's SecurityConfig exempts only OPTIONS and the swagger paths; every other
        // route, health checks included, goes through the same bearer-token verification.
        mockMvc.perform(get("/api/v1/advice/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthReturnsUpWithAValidToken() throws Exception {
        mockMvc.perform(get("/api/v1/advice/health").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
