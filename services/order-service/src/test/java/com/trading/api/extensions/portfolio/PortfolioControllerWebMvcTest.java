package com.trading.api.extensions.portfolio;

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
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = PortfolioController.class,
        properties = "app.security.jwt-secret=01234567890123456789012345678901"
)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class PortfolioControllerWebMvcTest {

    private static final String JWT_SECRET = "01234567890123456789012345678901";
    private static final long ACCOUNT = 4L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PortfolioService portfolioService;
    @MockBean
    private PortfolioHistoryService historyService;

    private String bearer() throws Exception {
        return "Bearer " + JwtTestTokens.validTokenFor(ACCOUNT, JWT_SECRET);
    }

    @Test
    void missingTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/4"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    void theSummaryIsReturnedForTheOwnAccount() throws Exception {
        when(portfolioService.summary(ACCOUNT, ACCOUNT)).thenReturn(new PortfolioSummary(
                ACCOUNT, "INR", new BigDecimal("1000.00"), new BigDecimal("35000.00"), new BigDecimal("34000.00"),
                new BigDecimal("1000.00"), new BigDecimal("2.94"), new BigDecimal("0.00"), new BigDecimal("36000.00"),
                1, false, Instant.now()));

        mockMvc.perform(get("/api/v1/portfolio/4").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("INR"))
                .andExpect(jsonPath("$.cashBalance").value(1000.00))
                .andExpect(jsonPath("$.marketValue").value(35000.00))
                .andExpect(jsonPath("$.totalValue").value(36000.00))
                .andExpect(jsonPath("$.byCurrency").doesNotExist())
                .andExpect(jsonPath("$.partial").value(false));
    }

    @Test
    void anotherAccountsPortfolioIsForbidden() throws Exception {
        when(portfolioService.summary(eq(5L), eq(ACCOUNT)))
                .thenThrow(new AccountNotActiveException("account is not active"));

        mockMvc.perform(get("/api/v1/portfolio/5").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    @Test
    void positionsAreReturned() throws Exception {
        when(portfolioService.positions(ACCOUNT, ACCOUNT)).thenReturn(List.of(new PricedPosition(
                ACCOUNT, "TCS.NS", 10, new BigDecimal("3400.00"), new BigDecimal("34000.00"), new BigDecimal("3500.00"),
                new BigDecimal("35000.00"), new BigDecimal("1000.00"), new BigDecimal("2.94"), "INR",
                Instant.parse("2026-10-01T09:00:00Z"), false)));

        mockMvc.perform(get("/api/v1/portfolio/4/positions").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("TCS.NS"))
                .andExpect(jsonPath("$[0].marketValue").value(35000.00));
    }

    @Test
    void whenNothingCanBePricedTheAnswerIs503() throws Exception {
        when(portfolioService.positions(ACCOUNT, ACCOUNT))
                .thenThrow(new PricingUnavailableException("pricing is unavailable for every holding"));

        mockMvc.perform(get("/api/v1/portfolio/4/positions").header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("MKT-503"));
    }

    @Test
    void pnlIsReturnedWithTheBreakdownWhenAskedFor() throws Exception {
        PnlResponse pnlResponse = new PnlResponse(ACCOUNT, "INR", null, null, new BigDecimal("500.00"),
                new BigDecimal("1000.00"), new BigDecimal("1500.00"),
                List.of(new SymbolPnl("TCS.NS", new BigDecimal("500.00"), new BigDecimal("1000.00"), new BigDecimal("1500.00"))),
                Instant.parse("2026-10-01T00:00:00Z"));
        when(portfolioService.pnl(eq(ACCOUNT), eq(ACCOUNT), isNull(), isNull(), eq(true))).thenReturn(pnlResponse);

        mockMvc.perform(get("/api/v1/portfolio/4/pnl").param("bySymbol", "true").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("INR"))
                .andExpect(jsonPath("$.totalPnl").value(1500.00))
                .andExpect(jsonPath("$.bySymbol[0].realisedPnl").value(500.00))
                .andExpect(jsonPath("$.bySymbol[0].unrealisedPnl").value(1000.00));
    }

    @Test
    void theBreakdownIsLeftOutByDefault() throws Exception {
        when(portfolioService.pnl(eq(ACCOUNT), eq(ACCOUNT), isNull(), isNull(), eq(false))).thenReturn(new PnlResponse(
                ACCOUNT, "INR", null, null, new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"), null,
                Instant.parse("2026-10-01T00:00:00Z")));

        mockMvc.perform(get("/api/v1/portfolio/4/pnl").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bySymbol").doesNotExist());
    }

    @Test
    void theDatesAreReadAsDays() throws Exception {
        when(portfolioService.pnl(eq(ACCOUNT), eq(ACCOUNT), eq(java.time.LocalDate.parse("2026-09-01")),
                eq(java.time.LocalDate.parse("2026-10-19")), eq(false))).thenReturn(new PnlResponse(
                ACCOUNT, "INR", java.time.LocalDate.parse("2026-09-01"), java.time.LocalDate.parse("2026-10-19"),
                new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("3.00"), null, Instant.now()));

        mockMvc.perform(get("/api/v1/portfolio/4/pnl").param("from", "2026-09-01").param("to", "2026-10-19")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-01"))
                .andExpect(jsonPath("$.to").value("2026-10-19"));
    }

    @Test
    void aMalformedDateIsRefusedAsInvalidInput() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/4/pnl").param("from", "yesterday").header("Authorization", bearer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }

    @Test
    void historyIsReturnedPerCurrencyForTheRequestedRange() throws Exception {
        when(historyService.history(ACCOUNT, ACCOUNT, "1W")).thenReturn(List.of(new PortfolioHistory("INR", "1W",
                Instant.parse("2026-09-30T05:00:00Z"), Instant.parse("2026-09-30T05:00:00Z"),
                List.of(new PortfolioHistory.Point(Instant.parse("2026-10-02T05:00:00Z"), new BigDecimal("389886.00"), new BigDecimal("595299.50"), false)))));

        mockMvc.perform(get("/api/v1/portfolio/4/history").param("range", "1W").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].points[0].value").value(389886.00))
                .andExpect(jsonPath("$[0].points[0].invested").value(595299.50));
    }

    @Test
    void historyDefaultsToAllTimeAndRefusesAnUnknownRange() throws Exception {
        when(historyService.history(ACCOUNT, ACCOUNT, "ALL")).thenReturn(List.of());
        when(historyService.history(ACCOUNT, ACCOUNT, "5Y")).thenThrow(new InvalidHistoryRangeException("range must be one of 1D, 1W, 1M or ALL"));

        mockMvc.perform(get("/api/v1/portfolio/4/history").header("Authorization", bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/portfolio/4/history").param("range", "5Y").header("Authorization", bearer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }
}
