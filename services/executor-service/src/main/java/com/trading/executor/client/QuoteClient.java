package com.trading.executor.client;

import com.trading.executor.domain.Quote;

import java.util.List;
import java.util.Map;

public interface QuoteClient {

    Quote getQuote(String symbol);

    Map<String, Quote> getBatchQuotes(List<String> symbols);
}
