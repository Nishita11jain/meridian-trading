package com.trading.api.extensions.watchlists;

import com.trading.api.JwtTestTokens;
import com.trading.api.config.SecurityConfig;
import com.trading.api.exception.GlobalExceptionHandler;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {MarketQuoteController.class, WatchlistsController.class, AlertsController.class},
        properties = "app.security.jwt-secret=01234567890123456789012345678901"
)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class WatchlistsWebMvcTest {

    private static final String JWT_SECRET = "01234567890123456789012345678901";
    private static final long ACCOUNT = 4L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WatchlistsService watchlistsService;
    @MockBean
    private MarketDataService marketDataService;
    @MockBean
    private MarketFreshnessService freshnessService;

    private String bearer() throws Exception {
        return "Bearer " + JwtTestTokens.validTokenFor(ACCOUNT, JWT_SECRET);
    }

    @Test
    void marketQuotesNeedAToken() throws Exception {
        mockMvc.perform(get("/api/v1/market/quotes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    void marketQuotesAreReturnedToAVerifiedToken() throws Exception {
        when(marketDataService.getAllQuotes()).thenReturn(List.of(new MarketQuoteResponse(
                "TCS.NS", new BigDecimal("3500.00"), null, null, "INR", null, null, null, "open", false,
                Instant.parse("2026-10-01T09:00:00Z"), null, null, null)));

        mockMvc.perform(get("/api/v1/market/quotes").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("TCS.NS"))
                .andExpect(jsonPath("$[0].currency").value("INR"));
    }

    @Test
    void marketHistoryAcceptsAChartRange() throws Exception {
        when(marketDataService.getHistory("TCS.NS", "1Y")).thenReturn(List.of(new MarketQuoteResponse(
                "TCS.NS", new BigDecimal("3500.00"), null, null, "INR", null, null, null, "open", false,
                Instant.parse("2026-10-01T09:00:00Z"), new BigDecimal("3490.00"), new BigDecimal("3520.00"), new BigDecimal("3480.00"))));

        mockMvc.perform(get("/api/v1/market/history/TCS.NS").param("range", "1Y").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("TCS.NS"));
    }

    @Test
    void marketHistoryRejectsAnUnknownRange() throws Exception {
        when(marketDataService.getHistory("TCS.NS", "10Y"))
                .thenThrow(new InvalidMarketHistoryRangeException("range must be one of 5D, 1M, 3M, 6M or 1Y"));

        mockMvc.perform(get("/api/v1/market/history/TCS.NS").param("range", "10Y").header("Authorization", bearer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }

    @Test
    void creatingAWatchlistReturns201() throws Exception {
        when(watchlistsService.createWatchlist(eq(ACCOUNT), any())).thenReturn(
                new WatchlistResponse(1L, "Tech", Instant.now(), List.of()));

        mockMvc.perform(post("/api/v1/watchlists").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Tech\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Tech"));
    }

    @Test
    void anotherAccountsWatchlistIs404() throws Exception {
        when(watchlistsService.getWatchlist(eq(ACCOUNT), eq(99L)))
                .thenThrow(new WatchlistNotFoundException("watchlist not found"));

        mockMvc.perform(get("/api/v1/watchlists/99").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WL-404"));
    }

    @Test
    void anInvalidAlertDirectionIsRejected422() throws Exception {
        mockMvc.perform(post("/api/v1/alerts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"TCS.NS\",\"direction\":\"SIDEWAYS\",\"targetPrice\":100}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }

    @Test
    void exceedingTheAlertCapIs429() throws Exception {
        when(watchlistsService.createAlert(eq(ACCOUNT), any()))
                .thenThrow(new AlertLimitException("too many alerts"));

        mockMvc.perform(post("/api/v1/alerts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"TCS.NS\",\"direction\":\"ABOVE\",\"targetPrice\":3500}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("LIMIT-429"));
    }

    @Test
    void cancellingAnAlertReturnsItsState() throws Exception {
        when(watchlistsService.cancelAlert(eq(ACCOUNT), eq(21L))).thenReturn(new AlertResponse(
                21L, "TCS.NS", "ABOVE", new BigDecimal("3500"), "CANCELLED", Instant.now(), null, null));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/alerts/21").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void freshnessSaysWhenTheNextPricesAreDue() throws Exception {
        when(freshnessService.freshness()).thenReturn(new MarketFreshnessResponse(
                Instant.parse("2026-10-02T05:00:00Z"), Instant.parse("2026-10-02T05:05:00Z"), 300));

        mockMvc.perform(get("/api/v1/market/freshness").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextUpdateAt").value("2026-10-02T05:05:00Z"))
                .andExpect(jsonPath("$.pollIntervalSeconds").value(300));
        mockMvc.perform(get("/api/v1/market/freshness"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWatchlistCanBeRenamedReorderedAndDeleted() throws Exception {
        WatchlistResponse renamed = new WatchlistResponse(1L, "Banks", Instant.now(), List.of());
        when(watchlistsService.renameWatchlist(eq(ACCOUNT), eq(1L), any())).thenReturn(renamed);
        when(watchlistsService.reorderInstruments(eq(ACCOUNT), eq(1L), any())).thenReturn(renamed);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/watchlists/1")
                        .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Banks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Banks"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/watchlists/1/instruments/order")
                        .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"symbols\":[\"TCS.NS\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/watchlists/1")
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(watchlistsService).deleteWatchlist(ACCOUNT, 1L);
    }

    @Test
    void renamingToABlankNameIsVal422() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/watchlists/1")
                        .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }

    @Test
    void readyMadeListsAreListedAndOneCanBeCreated() throws Exception {
        when(watchlistsService.presets()).thenReturn(List.of(new WatchlistPresetResponse("NSE", "NSE stocks", List.of("TCS.NS"))));
        when(watchlistsService.createFromPreset(ACCOUNT, "NSE")).thenReturn(new WatchlistResponse(5L, "NSE stocks", Instant.now(), List.of()));

        mockMvc.perform(get("/api/v1/watchlists/presets").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("NSE stocks"))
                .andExpect(jsonPath("$[0].symbols[0]").value("TCS.NS"));
        mockMvc.perform(post("/api/v1/watchlists/presets/NSE").header("Authorization", bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void anInstrumentCanBeFlagged() throws Exception {
        when(watchlistsService.setItemFlag(eq(ACCOUNT), eq(1L), eq("TCS.NS"), any()))
                .thenReturn(new WatchlistResponse(1L, "Tech", Instant.now(), List.of(new WatchlistItemResponse("TCS.NS", null, "GREEN"))));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/watchlists/1/instruments/TCS.NS/flag")
                        .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"flag\":\"GREEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].flag").value("GREEN"));
    }

    @Test
    void aFlagThatIsNotAColourIsVal422() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/watchlists/1/instruments/TCS.NS/flag")
                        .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"flag\":\"purple\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }
}
