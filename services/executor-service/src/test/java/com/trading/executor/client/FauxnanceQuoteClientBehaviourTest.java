package com.trading.executor.client;

import com.trading.executor.domain.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * FauxnanceQuoteClient over a REAL RestClient talking to Spring's MockRestServiceServer, so the HTTP
 * behaviour is exercised for real: the request that is sent, the answer parsed, and what happens when
 * the service fails, answers nonsense, or the daily quota has run out. The client is the only link to
 * the market-data API, and a bad answer here must become "no quote" (which the executor rejects), never
 * an exception or a wrong price.
 */
class FauxnanceQuoteClientBehaviourTest {

    private static final String BASE = "http://fauxnance.test";

    private MockRestServiceServer server;
    private FauxnanceQuotaTracker quota;
    private FauxnanceQuoteClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        quota = mock(FauxnanceQuotaTracker.class);
        when(quota.tryReserve(1)).thenReturn(true);
        client = new FauxnanceQuoteClient(builder.build(), "test-key", quota);
    }

    private static String flat(String symbol, String quoteFields) {
        return "{\"data\":{\"symbol\":\"" + symbol + "\"," + quoteFields + "}}";
    }

    private static final String GOOD_FIELDS =
            "\"price\":232.80,\"bid\":232.71,\"ask\":232.89,\"currency\":\"USD\",\"asOf\":\"2026-09-17T00:00:00Z\",\"marketState\":\"OPEN\"";

    // ---------------------------------------------------------------- getQuote: what is sent and parsed

    @Test
    void getQuoteSendsTheApiKeyAndAsksForTheSymbol() {
        server.expect(requestTo(BASE + "/quotes/INFY"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Api-Key", "test-key"))
                .andRespond(withSuccess(flat("INFY", GOOD_FIELDS), MediaType.APPLICATION_JSON));

        Quote quote = client.getQuote("INFY");

        server.verify();
        assertEquals("INFY", quote.symbol());
        assertEquals(0, new BigDecimal("232.71").compareTo(quote.bid()));
        assertEquals(0, new BigDecimal("232.89").compareTo(quote.ask()));
        assertEquals("USD", quote.currency());
        assertEquals(Instant.parse("2026-09-17T00:00:00Z"), quote.asOf());
    }

    @Test
    void getQuoteReadsAQuoteNestedUnderData() {
        server.expect(requestTo(BASE + "/quotes/TCS"))
                .andRespond(withSuccess("{\"data\":{\"quote\":{\"price\":99.25,\"bid\":99.20,\"ask\":99.30}}}", MediaType.APPLICATION_JSON));

        Quote quote = client.getQuote("TCS");

        assertEquals("TCS", quote.symbol());   // the symbol falls back to the one asked for
        assertEquals(0, new BigDecimal("99.20").compareTo(quote.bid()));
    }

    @Test
    void getQuoteMarksAStaleQuoteAndLowercasesTheMarketState() {
        server.expect(requestTo(BASE + "/quotes/INFY"))
                .andRespond(withSuccess("{\"data\":{\"stale\":true,\"symbol\":\"INFY\",\"price\":1,\"bid\":1,\"ask\":1,\"marketState\":\"CLOSED\"}}", MediaType.APPLICATION_JSON));

        Quote quote = client.getQuote("INFY");

        assertTrue(quote.stale());
        assertEquals("closed", quote.marketState());
    }

    @Test
    void getQuoteTreatsMissingOptionalFieldsAsUnknownNotAsAnError() {
        server.expect(requestTo(BASE + "/quotes/INFY"))
                .andRespond(withSuccess(flat("INFY", "\"price\":5,\"bid\":4.9,\"ask\":5.1"), MediaType.APPLICATION_JSON));

        Quote quote = client.getQuote("INFY");

        assertEquals("unknown", quote.marketState());
        assertNull(quote.change());
        assertNull(quote.asOf());
        assertNull(quote.currency());
        assertFalse(quote.stale());
    }

    @Test
    void getQuoteIgnoresAnOptionalNumberOrTimestampItCannotParse() {
        server.expect(requestTo(BASE + "/quotes/INFY"))
                .andRespond(withSuccess(flat("INFY", "\"price\":5,\"bid\":4.9,\"ask\":5.1,\"change\":\"n/a\",\"asOf\":\"yesterday\""), MediaType.APPLICATION_JSON));

        Quote quote = client.getQuote("INFY");

        assertNull(quote.change());
        assertNull(quote.asOf());
        assertEquals(0, new BigDecimal("5.1").compareTo(quote.ask()));
    }

    @Test
    void getQuoteGivesNoQuoteWhenTheBidAskOrPriceIsMissing() {
        server.expect(requestTo(BASE + "/quotes/A")).andRespond(withSuccess(flat("A", "\"price\":5,\"ask\":5.1"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/quotes/B")).andRespond(withSuccess(flat("B", "\"price\":5,\"bid\":4.9"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/quotes/C")).andRespond(withSuccess(flat("C", "\"bid\":4.9,\"ask\":5.1"), MediaType.APPLICATION_JSON));

        assertNull(client.getQuote("A"));
        assertNull(client.getQuote("B"));
        assertNull(client.getQuote("C"));
    }

    @Test
    void getQuoteGivesNoQuoteWhenTheBodyHasNoDataOrIsEmpty() {
        server.expect(requestTo(BASE + "/quotes/A")).andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/quotes/B")).andRespond(withSuccess("{\"data\":\"not an object\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/quotes/C")).andRespond(withSuccess());

        assertNull(client.getQuote("A"));
        assertNull(client.getQuote("B"));
        assertNull(client.getQuote("C"));
    }

    // ---------------------------------------------------------------- getQuote: failures, retries, quota

    @Test
    void getQuoteRetriesAfterAServerErrorAndReturnsTheQuoteThatFollows() {
        server.expect(requestTo(BASE + "/quotes/INFY")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(BASE + "/quotes/INFY")).andRespond(withSuccess(flat("INFY", GOOD_FIELDS), MediaType.APPLICATION_JSON));

        Quote quote = client.getQuote("INFY");

        server.verify();
        assertEquals("INFY", quote.symbol());
        verify(quota, times(2)).tryReserve(1);   // every attempt, a retry included, spends one request of the quota
    }

    @Test
    void getQuoteGivesUpAfterThreeFailedAttemptsAndReturnsNoQuote() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(BASE + "/quotes/INFY")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        assertNull(client.getQuote("INFY"));

        server.verify();
        verify(quota, times(3)).tryReserve(1);
    }

    @Test
    void getQuoteTreatsATooManyRequestsAnswerAsAFailureToRetry() {
        server.expect(requestTo(BASE + "/quotes/INFY")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(BASE + "/quotes/INFY")).andRespond(withSuccess(flat("INFY", GOOD_FIELDS), MediaType.APPLICATION_JSON));

        assertEquals("INFY", client.getQuote("INFY").symbol());
    }

    @Test
    void getQuoteTreatsAnUnknownSymbolAnswerAsNoQuote() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(BASE + "/quotes/NOPE")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        }

        assertNull(client.getQuote("NOPE"));
    }

    @Test
    void getQuoteTreatsAnAnswerThatIsNotJsonAsAFailureNotACrash() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(BASE + "/quotes/INFY")).andRespond(withSuccess("<html>gateway error</html>", MediaType.APPLICATION_JSON));
        }

        assertNull(client.getQuote("INFY"));
    }

    @Test
    void getQuoteMakesNoRequestOnceTheDailyQuotaIsSpent() {
        when(quota.tryReserve(1)).thenReturn(false);
        when(quota.remainingToday()).thenReturn(0);

        assertNull(client.getQuote("INFY"));

        server.verify();   // no request was expected, and none was made
        verify(quota, times(1)).tryReserve(1);
    }

    @Test
    void getQuoteStopsRetryingWhenTheQuotaRunsOutBetweenAttempts() {
        when(quota.tryReserve(1)).thenReturn(true, false);
        server.expect(requestTo(BASE + "/quotes/INFY")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertNull(client.getQuote("INFY"));

        server.verify();
        verify(quota, times(2)).tryReserve(1);
    }

    // ---------------------------------------------------------------- getBatchQuotes

    private static String batchEntry(String symbol, String quoteFields) {
        return "{\"symbol\":\"" + symbol + "\",\"stale\":false,\"quote\":{\"symbol\":\"" + symbol + "\"," + quoteFields + "}}";
    }

    private static String batch(String... entries) {
        return "{\"data\":{\"quotes\":[" + String.join(",", entries) + "]}}";
    }

    @Test
    void getBatchQuotesAsksForAllSymbolsInOneCallAndKeysTheResultBySymbol() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quotes")))
                .andExpect(queryParam("symbols", "INFY,TCS"))
                .andExpect(header("X-Api-Key", "test-key"))
                .andRespond(withSuccess(batch(batchEntry("INFY", GOOD_FIELDS), batchEntry("TCS", GOOD_FIELDS)), MediaType.APPLICATION_JSON));

        Map<String, Quote> quotes = client.getBatchQuotes(List.of("INFY", "TCS"));

        server.verify();
        assertEquals(List.of("INFY", "TCS"), List.copyOf(quotes.keySet()));
        verify(quota, times(1)).tryReserve(1);   // one call, one request of the quota, whatever the symbol count
    }

    @Test
    void getBatchQuotesSkipsAnEntryItCannotUseAndKeepsTheRest() {
        String noQuote = "{\"symbol\":\"BAD1\"}";
        String noPrice = batchEntry("BAD2", "\"bid\":1,\"ask\":1");
        String notAnObject = "\"BAD3\"";
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quotes")))
                .andRespond(withSuccess(batch(noQuote, noPrice, notAnObject, batchEntry("INFY", GOOD_FIELDS)), MediaType.APPLICATION_JSON));

        Map<String, Quote> quotes = client.getBatchQuotes(List.of("BAD1", "BAD2", "BAD3", "INFY"));

        assertEquals(List.of("INFY"), List.copyOf(quotes.keySet()));
    }

    @Test
    void getBatchQuotesGivesNothingWhenTheAnswerHasNoQuotesList() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quotes"))).andRespond(withSuccess("{\"data\":{\"quotes\":\"none\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quotes"))).andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quotes"))).andRespond(withSuccess());

        assertTrue(client.getBatchQuotes(List.of("INFY")).isEmpty());
        assertTrue(client.getBatchQuotes(List.of("INFY")).isEmpty());
        assertTrue(client.getBatchQuotes(List.of("INFY")).isEmpty());
    }

    @Test
    void getBatchQuotesDoesNothingForNoSymbols() {
        assertTrue(client.getBatchQuotes(null).isEmpty());
        assertTrue(client.getBatchQuotes(List.of()).isEmpty());

        server.verify();
        verify(quota, never()).tryReserve(1);
    }

    @Test
    void getBatchQuotesRefusesMoreThanTwentyFiveSymbols() {
        List<String> tooMany = java.util.stream.IntStream.rangeClosed(1, 26).mapToObj(i -> "S" + i).toList();

        assertThrows(IllegalArgumentException.class, () -> client.getBatchQuotes(tooMany));

        verify(quota, never()).tryReserve(1);
    }

    @Test
    void getBatchQuotesRetriesAfterAFailureAndReturnsTheQuotesThatFollow() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quotes"))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quotes")))
                .andRespond(withSuccess(batch(batchEntry("INFY", GOOD_FIELDS)), MediaType.APPLICATION_JSON));

        Map<String, Quote> quotes = client.getBatchQuotes(List.of("INFY"));

        server.verify();
        assertEquals(List.of("INFY"), List.copyOf(quotes.keySet()));
        verify(quota, times(2)).tryReserve(1);
    }

    @Test
    void getBatchQuotesGivesNothingAfterThreeFailedAttempts() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quotes"))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        }

        assertTrue(client.getBatchQuotes(List.of("INFY")).isEmpty());

        server.verify();
        verify(quota, times(3)).tryReserve(1);
    }

    @Test
    void getBatchQuotesMakesNoRequestOnceTheDailyQuotaIsSpent() {
        when(quota.tryReserve(1)).thenReturn(false);
        when(quota.remainingToday()).thenReturn(0);

        assertTrue(client.getBatchQuotes(List.of("INFY")).isEmpty());

        server.verify();
    }
}
