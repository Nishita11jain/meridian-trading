package com.trading.api.extensions.assistant;

import com.trading.api.extensions.portfolio.PortfolioService;
import com.trading.api.extensions.portfolio.PricedPosition;
import com.trading.api.extensions.portfolio.PricingUnavailableException;
import com.trading.api.extensions.watchlists.MarketDataService;
import com.trading.api.extensions.watchlists.MarketQuoteResponse;
import com.trading.domain.exception.InstrumentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssistantServiceTest {

    private static final long ACCOUNT = 4L;
    private static final Instant STORED = Instant.parse("2026-10-05T10:00:00Z");

    private PortfolioService portfolio;
    private MarketDataService market;
    private GeminiClient gemini;
    private AssistantService service;

    @BeforeEach
    void setUp() {
        portfolio = mock(PortfolioService.class);
        market = mock(MarketDataService.class);
        gemini = mock(GeminiClient.class);
        AssistantRateLimiter limiter = new AssistantRateLimiter(100, Clock.systemUTC());
        service = new AssistantService(portfolio, market, gemini, limiter);
        when(market.getAllQuotes()).thenReturn(List.of());
        when(gemini.generate(anyString(), anyString())).thenReturn("An answer.");
    }

    @Test
    void an_empty_question_is_refused_before_anything_else() {
        assertThatThrownBy(() -> service.chat(ACCOUNT, "   "))
                .isInstanceOf(AssistantMessageException.class);
        verifyNoInteractions(portfolio, market, gemini);
    }

    @Test
    void a_question_over_the_length_limit_is_refused() {
        String tooLong = "x".repeat(AssistantService.MAX_MESSAGE_LENGTH + 1);

        assertThatThrownBy(() -> service.chat(ACCOUNT, tooLong))
                .isInstanceOf(AssistantMessageException.class);
        verifyNoInteractions(gemini);
    }

    @Test
    void the_model_receives_words_and_never_the_account_amounts() {
        PricedPosition holding = new PricedPosition(ACCOUNT, "TCS.NS", 7, new BigDecimal("100"), new BigDecimal("700"),
                new BigDecimal("123.45"), new BigDecimal("864.15"), new BigDecimal("164.15"), null, "INR",
                STORED, false);
        when(portfolio.positions(ACCOUNT, ACCOUNT)).thenReturn(List.of(holding));
        when(market.getQuote("TCS.NS")).thenReturn(new MarketQuoteResponse("TCS.NS", new BigDecimal("123.45"),
            null, null, "INR", null, new BigDecimal("1.84"), null, "OPEN", false, STORED,
            null, null, null));

        service.chat(ACCOUNT, "How is my portfolio doing?");

        verify(gemini).generate(eq(AssistantService.SYSTEM_PROMPT), contains("TCS.NS"));
        verify(gemini).generate(anyString(), org.mockito.ArgumentMatchers.argThat(prompt ->
                !prompt.contains("864") && !prompt.contains("164.15") && !prompt.contains("700")));
    }

    @Test
    void a_fully_unpriced_portfolio_still_gets_an_answer_with_no_holdings_facts() {
        when(portfolio.positions(ACCOUNT, ACCOUNT)).thenThrow(new PricingUnavailableException("no prices"));

        assertThat(service.chat(ACCOUNT, "What do I hold?").reply()).isEqualTo("An answer.");
        verify(gemini).generate(anyString(), contains("The user holds no positions."));
    }

    @Test
    void a_holding_with_no_stored_quote_is_left_out_without_failing() {
        PricedPosition holding = new PricedPosition(ACCOUNT, "NEW.NS", 1, BigDecimal.ONE, BigDecimal.ONE, null, null,
                null, null, "INR", null, true);
        when(portfolio.positions(ACCOUNT, ACCOUNT)).thenReturn(List.of(holding));
        when(market.getQuote("NEW.NS")).thenThrow(new InstrumentNotFoundException("no quote"));

        assertThat(service.chat(ACCOUNT, "Anything on NEW.NS?").reply()).isEqualTo("An answer.");
    }

    @Test
    void an_over_limit_account_is_stopped_before_the_model_is_called() {
        AssistantRateLimiter tight = new AssistantRateLimiter(1, Clock.systemUTC());
        AssistantService limited = new AssistantService(portfolio, market, gemini, tight);
        when(portfolio.positions(ACCOUNT, ACCOUNT)).thenReturn(List.of());

        limited.chat(ACCOUNT, "first");

        assertThatThrownBy(() -> limited.chat(ACCOUNT, "second"))
                .isInstanceOf(AssistantRateLimitedException.class);
        verify(gemini, org.mockito.Mockito.times(1)).generate(anyString(), anyString());
        verify(gemini, never()).generate(anyString(), contains("second"));
    }
}
