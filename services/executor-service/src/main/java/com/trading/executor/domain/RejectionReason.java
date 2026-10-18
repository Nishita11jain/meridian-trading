package com.trading.executor.domain;

/**
 * Why the executor rejected an order, as the stable tokens contracts/event-schemas/kafka-topics.md describes for
 * the `reason` field of a trade-events message (for example INSUFFICIENT_FUNDS, PRICE_NOT_MET,
 * INSTRUMENT_NOT_TRADABLE). A consumer can branch on these; free text cannot be branched on safely.
 */
public final class RejectionReason {

    public static final String INSTRUMENT_NOT_TRADABLE = "INSTRUMENT_NOT_TRADABLE";
    public static final String ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED";
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String INSUFFICIENT_HOLDINGS = "INSUFFICIENT_HOLDINGS";
    /** The limit price could not be met at the quote: a buy below the ask, or a sell above the bid. */
    public static final String PRICE_NOT_MET = "PRICE_NOT_MET";
    public static final String PRICING_UNAVAILABLE = "PRICING_UNAVAILABLE";
    /** The instrument's market had closed by the time the order was settled (decision log 0017). */
    public static final String MARKET_CLOSED = "MARKET_CLOSED";

    private RejectionReason() {
    }
}
