package com.trading.api;

import com.trading.api.config.SecurityConfig;
import com.trading.api.exception.GlobalExceptionHandler;
import com.trading.api.service.AccountService;
import com.trading.api.service.OrderService;
import com.trading.domain.exception.AccountNotActiveException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = {com.trading.api.controller.AccountController.class, com.trading.api.controller.OrderController.class},
    properties = "app.security.jwt-secret=01234567890123456789012345678901"
)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SecurityAndAccountAccessWebMvcTest {

    private static final String JWT_SECRET = "01234567890123456789012345678901";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountService accountService;

    @MockBean
    private OrderService orderService;

    @Test
    void missingTokenReturnsAuth401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"))
                .andExpect(jsonPath("$.message").value("Unauthorised"));
    }

    @Test
    void wrongSchemeReturnsAuth401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1").header("Authorization", "Basic not-a-bearer-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    void expiredTokenReturnsAuth401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1").header("Authorization", expiredBearerToken(1L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    void correctlySignedTokenFromAnotherIssuerReturnsAuth401() throws Exception {
        String forged = JwtTestTokens.tokenWithIssuer(1L, "some-other-service", JWT_SECRET);

        mockMvc.perform(get("/api/v1/accounts/1").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"))
                .andExpect(jsonPath("$.message").value("Unauthorised"));
    }

    @Test
    void correctlySignedTokenWithNoIssuerReturnsAuth401() throws Exception {
        String noIssuer = JwtTestTokens.tokenWithIssuer(1L, null, JWT_SECRET);

        mockMvc.perform(get("/api/v1/accounts/1").header("Authorization", "Bearer " + noIssuer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    void tokenFromTheConfiguredIssuerIsAccepted() throws Exception {
        String genuine = JwtTestTokens.tokenWithIssuer(1L, "auth-service", JWT_SECRET);

        mockMvc.perform(get("/api/v1/accounts/1").header("Authorization", "Bearer " + genuine))
                .andExpect(status().isOk());
    }

    @Test
    void correctlySignedTokenWithNoAccountIdReturnsAuth401NotA500() throws Exception {
        String noAccount = JwtTestTokens.tokenWithoutAccountId(JWT_SECRET);

        mockMvc.perform(get("/api/v1/accounts/1").header("Authorization", "Bearer " + noAccount))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"))
                .andExpect(jsonPath("$.message").value("Unauthorised"));
    }

    @Test
    void anAccountIdThatIsNotAPositiveWholeNumberReturnsAuth401() throws Exception {
        for (Object badClaim : new Object[] {"1", "abc", 0, -1, 1.5, true}) {
            String token = JwtTestTokens.tokenWithAccountIdClaim(badClaim, JWT_SECRET);

            mockMvc.perform(get("/api/v1/accounts/1").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
        }
    }

    @Test
    void theAccountComesFromTheClaimNotFromTheSubject() throws Exception {
        // Token for account 7; the subject is a UUID that means nothing to the service.
        String token = JwtTestTokens.validTokenFor(7L, JWT_SECRET);

        mockMvc.perform(get("/api/v1/accounts/7").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(accountService).getAccount(7L, 7L);
    }

    @Test
    void tamperedTokenReturnsAuth401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1").header("Authorization", tamperedBearerToken(1L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
void validTokenForWrongAccountReturns403() throws Exception {
    when(accountService.getAccount(2L, 1L))
            .thenThrow(new AccountNotActiveException("account is not active"));

    mockMvc.perform(get("/api/v1/accounts/2").header("Authorization", bearerToken(1L)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACC-403"))
            .andExpect(jsonPath("$.message").value("account is not active"));
}

    private static String bearerToken(long accountId) throws Exception {
        return "Bearer " + JwtTestTokens.validTokenFor(accountId, JWT_SECRET);
    }

    private static String expiredBearerToken(long accountId) throws Exception {
        return "Bearer " + JwtTestTokens.expiredTokenFor(accountId, JWT_SECRET);
    }

    private static String tamperedBearerToken(long accountId) throws Exception {
    String token = JwtTestTokens.validTokenFor(accountId, JWT_SECRET);
    int payloadStart = token.indexOf('.') + 1;
    char original = token.charAt(payloadStart);
    char replacement = original == 'e' ? 'f' : 'e';
    return "Bearer " + token.substring(0, payloadStart) + replacement + token.substring(payloadStart + 1);
}
}
