package com.trading.api.extensions.preferences;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The preferences routes through the real security filter chain: a missing or forged token is
 * refused before the controller, a valid token reaches it, a token for another account is refused
 * ACC-403, and a malformed body is VAL-422. The service is mocked; this is about the wire contract
 * and the authorisation boundary.
 */
@WebMvcTest(
        controllers = PreferencesController.class,
        properties = "app.security.jwt-secret=01234567890123456789012345678901"
)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class PreferencesControllerWebMvcTest {

    private static final String JWT_SECRET = "01234567890123456789012345678901";
    private static final long ACCOUNT = 4L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PreferencesService preferencesService;

    private String bearer(long accountId) throws Exception {
        return "Bearer " + JwtTestTokens.validTokenFor(accountId, JWT_SECRET);
    }

    private static PreferenceResponse sample() {
        return new PreferenceResponse(ACCOUNT, ACCOUNT, "EMAIL", "a@b.com", null, "USD", "DARK", "avatar_02", null);
    }

    @Test
    void missingTokenIsRefusedBeforeTheController() throws Exception {
        mockMvc.perform(get("/api/v1/preferences/4"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
        verify(preferencesService, never()).getPreferences(any(), any());
    }

    @Test
    void forgedSignatureIsRefused() throws Exception {
        String forged = JwtTestTokens.validTokenFor(ACCOUNT, "the-wrong-secret-the-wrong-secret!!");
        mockMvc.perform(get("/api/v1/preferences/4").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    void aValidTokenReadsItsOwnPreferences() throws Exception {
        when(preferencesService.getPreferences(ACCOUNT, ACCOUNT)).thenReturn(sample());

        mockMvc.perform(get("/api/v1/preferences/4").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationChannel").value("EMAIL"))
                .andExpect(jsonPath("$.displayCurrency").value("USD"));
    }

    @Test
    void readingAnotherAccountIsRefusedAcc403() throws Exception {
        when(preferencesService.getPreferences(eq(5L), eq(ACCOUNT)))
                .thenThrow(new AccountNotActiveException("account is not active"));

        mockMvc.perform(get("/api/v1/preferences/5").header("Authorization", bearer(ACCOUNT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    @Test
    void savingNotificationSettingsReturnsTheSavedRow() throws Exception {
        when(preferencesService.updateNotification(eq(ACCOUNT), eq(ACCOUNT), any())).thenReturn(sample());

        mockMvc.perform(put("/api/v1/preferences/4/notifications")
                        .header("Authorization", bearer(ACCOUNT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notificationChannel\":\"EMAIL\",\"contactEmail\":\"a@b.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationChannel").value("EMAIL"));
    }

    @Test
    void aMalformedEmailIsRejectedAsVal422BeforeTheService() throws Exception {
        mockMvc.perform(put("/api/v1/preferences/4/notifications")
                        .header("Authorization", bearer(ACCOUNT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notificationChannel\":\"EMAIL\",\"contactEmail\":\"not-an-email\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
        verify(preferencesService, never()).updateNotification(any(), any(), any());
    }

    @Test
    void aBlankCurrencyIsRejectedAsVal422() throws Exception {
        mockMvc.perform(put("/api/v1/preferences/4/display")
                        .header("Authorization", bearer(ACCOUNT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayCurrency\":\"\",\"theme\":\"DARK\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }

    @Test
    void savingDisplaySettingsReturnsTheSavedRow() throws Exception {
        when(preferencesService.updateDisplay(eq(ACCOUNT), eq(ACCOUNT), any())).thenReturn(sample());

        mockMvc.perform(put("/api/v1/preferences/4/display")
                        .header("Authorization", bearer(ACCOUNT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayCurrency\":\"USD\",\"theme\":\"DARK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayCurrency").value("USD"));
    }
}
