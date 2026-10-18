package com.trading.api.extensions.assistant;

import com.trading.api.extensions.portfolio.PricedPosition;
import com.trading.api.extensions.watchlists.MarketQuoteResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantContextTest {

    private static final Instant STORED = Instant.parse("2026-10-05T10:00:00Z");

    private static PricedPosition position(String symbol, String marketValue, String pnl, String currency) {
        return new PricedPosition(1L, symbol, 7, new BigDecimal("100.00"), new BigDecimal("700.00"),
                new BigDecimal("123.45"), new BigDecimal(marketValue),
                pnl == null ? null : new BigDecimal(pnl), null, currency, STORED, false);
    }

    private static MarketQuoteResponse quote(String symbol, String changePercent) {
        return new MarketQuoteResponse(symbol, new BigDecimal("123.45"), null, null, "INR", null,
                                changePercent == null ? null : new BigDecimal(changePercent), null, "OPEN", false, STORED,
                                null, null, null);
    }

    @Test
    void no_money_figure_reaches_the_facts() {
        List<PricedPosition> positions = List.of(
                position("TCS.NS", "98765.43", "1234.56", "INR"),
                position("INFY.NS", "4321.10", "-210.99", "INR"));

        String facts = AssistantContext.describe(positions, List.of(quote("TCS.NS", "1.84")));

        assertThat(facts).doesNotContain("98765").doesNotContain("98,765")
                .doesNotContain("1234").doesNotContain("1,234")
                .doesNotContain("4321").doesNotContain("210.99")
                .doesNotContain("123.45").doesNotContain("700")
                .doesNotContain("INR");
    }

    @Test
    void a_holding_is_described_by_direction_against_cost_only() {
        assertThat(AssistantContext.directionAgainstCost(position("A.NS", "1", "5.00", "INR")))
                .isEqualTo("currently above your average cost");
        assertThat(AssistantContext.directionAgainstCost(position("A.NS", "1", "-5.00", "INR")))
                .isEqualTo("currently below your average cost");
        assertThat(AssistantContext.directionAgainstCost(position("A.NS", "1", "0", "INR")))
                .isEqualTo("currently at your average cost");
        assertThat(AssistantContext.directionAgainstCost(position("A.NS", "1", null, "INR")))
                .isEqualTo("no current price is available");
    }

    @Test
    void a_dominant_holding_in_a_single_currency_portfolio_is_called_out() {
        List<PricedPosition> positions = List.of(
                position("BIG.NS", "900", "1", "INR"),
                position("SMALL.NS", "100", "1", "INR"));

        assertThat(AssistantContext.describe(positions, List.of()))
                .contains("BIG.NS is the largest holding, and it makes up most of the portfolio");
    }

    @Test
    void an_even_split_names_the_largest_holding_without_calling_it_dominant() {
        List<PricedPosition> positions = List.of(
                position("A.NS", "500", "1", "INR"),
                position("B.NS", "500", "1", "INR"));

        String facts = AssistantContext.describe(positions, List.of());

        assertThat(facts).contains("is the largest holding").doesNotContain("most of the portfolio");
    }

    @Test
    void holdings_in_several_currencies_get_no_share_claim() {
        List<PricedPosition> positions = List.of(
                position("A.NS", "900", "1", "INR"),
                position("B", "100", "1", "USD"));

        assertThat(AssistantContext.describe(positions, List.of())).doesNotContain("largest holding");
    }

    @Test
    void a_stock_move_is_a_rounded_percentage_with_its_direction() {
        assertThat(AssistantContext.moveSentence(quote("A.NS", "2.04")))
                .isEqualTo("about 2.0% up against the previous close");
        assertThat(AssistantContext.moveSentence(quote("A.NS", "-1.26")))
                .isEqualTo("about 1.3% down against the previous close");
        assertThat(AssistantContext.moveSentence(quote("A.NS", "0.02")))
                .isEqualTo("flat against the previous close");
        assertThat(AssistantContext.moveSentence(quote("A.NS", null)))
                .isEqualTo("no move against the previous close is stored");
    }

    @Test
    void an_empty_portfolio_says_so_and_still_lists_quotes() {
        String facts = AssistantContext.describe(List.of(), List.of(quote("X:BTC", "3.0")));

        assertThat(facts).startsWith("The user holds no positions.");
        assertThat(facts).contains("Latest stored prices (not live");
        assertThat(facts).contains("X:BTC: about 3.0% up");
    }
}
