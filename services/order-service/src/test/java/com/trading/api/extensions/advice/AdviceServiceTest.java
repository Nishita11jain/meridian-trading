package com.trading.api.extensions.advice;

import com.trading.api.extensions.watchlists.WatchlistRow;
import com.trading.api.extensions.watchlists.WatchlistsMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.PositionRow;
import com.trading.domain.Money;
import com.trading.domain.exception.AccountNotActiveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The advice service over stand-in mappers and a stand-in Fauxnance client: the eligible-symbol
 * rule (held or watched, nothing else), the cross-account refusal, the cache-aside behaviour of
 * "Analyse now" (cached inside the cooldown, fetched past it, falling back to a stale cache on a
 * failed refresh, and failing outright only when there is nothing cached at all).
 */
class AdviceServiceTest {

    private static final long ACCOUNT = 4L;
    private static final long OTHER = 5L;
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private CandleCacheMapper candleCacheMapper;
    private TradeSignalMapper tradeSignalMapper;
    private PositionMapper positionMapper;
    private WatchlistsMapper watchlistsMapper;
    private FauxnanceCandleClient candleClient;
    private AdviceService service;

    @BeforeEach
    void setUp() {
        candleCacheMapper = mock(CandleCacheMapper.class);
        tradeSignalMapper = mock(TradeSignalMapper.class);
        positionMapper = mock(PositionMapper.class);
        watchlistsMapper = mock(WatchlistsMapper.class);
        candleClient = mock(FauxnanceCandleClient.class);

        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AdviceService(
                candleCacheMapper, tradeSignalMapper, positionMapper, watchlistsMapper, candleClient,
                fixedClock, 24L);

        when(positionMapper.findAllHeldByAccount(ACCOUNT)).thenReturn(List.of());
        when(watchlistsMapper.findByAccount(ACCOUNT)).thenReturn(List.of());
    }

    private static PositionRow held(String symbol, int quantity) {
        PositionRow row = new PositionRow();
        row.setAccountId(ACCOUNT);
        row.setSymbol(symbol);
        row.setQuantity(quantity);
        row.setAverageCost(Money.of(BigDecimal.TEN));
        return row;
    }

    private static WatchlistRow watchlist(long id) {
        WatchlistRow row = new WatchlistRow();
        row.setId(id);
        row.setAccountId(ACCOUNT);
        row.setName("My list");
        return row;
    }

    private static TradeSignalRow signalRow(String symbol, Instant computedAt, Instant expiresAt) {
        TradeSignalRow row = new TradeSignalRow();
        row.setSymbol(symbol);
        row.setDirection("BUY");
        row.setStrength(new BigDecimal("0.5"));
        row.setRationale("test rationale");
        row.setComputedAt(computedAt);
        row.setExpiresAt(expiresAt);
        return row;
    }

    @Nested
    class Ownership {

        @Test
        void listSignalsRefusesAMismatchedAccount() {
            assertThrows(AccountNotActiveException.class, () -> service.listSignals(ACCOUNT, OTHER));
        }

        @Test
        void analyseRefusesAMismatchedAccount() {
            assertThrows(AccountNotActiveException.class, () -> service.analyse(ACCOUNT, OTHER, "AAA"));
        }
    }

    @Nested
    class Eligibility {

        @Test
        void aSymbolNeitherHeldNorWatchedIsRefused() {
            assertThrows(AdviceNotEligibleException.class, () -> service.analyse(ACCOUNT, ACCOUNT, "RANDOM"));
        }

        @Test
        void listSignalsCombinesHeldAndWatchedSymbolsWithNoDuplicates() {
            when(positionMapper.findAllHeldByAccount(ACCOUNT)).thenReturn(List.of(held("AAA", 10), held("ZZZ", 0)));
            when(watchlistsMapper.findByAccount(ACCOUNT)).thenReturn(List.of(watchlist(1L)));
            when(watchlistsMapper.findItemSymbols(1L)).thenReturn(List.of("AAA", "BBB"));
            when(tradeSignalMapper.findBySymbols(any())).thenReturn(List.of());

            List<SignalResponse> signals = service.listSignals(ACCOUNT, ACCOUNT);

            // AAA appears once despite being both held and watched; ZZZ is excluded (zero quantity).
            assertEquals(2, signals.size());
            assertTrue(signals.stream().anyMatch(s -> s.symbol().equals("AAA")));
            assertTrue(signals.stream().anyMatch(s -> s.symbol().equals("BBB")));
        }

        @Test
        void aSymbolNeverAnalysedCarriesANullDirection() {
            when(positionMapper.findAllHeldByAccount(ACCOUNT)).thenReturn(List.of(held("AAA", 10)));
            when(tradeSignalMapper.findBySymbols(any())).thenReturn(List.of());

            List<SignalResponse> signals = service.listSignals(ACCOUNT, ACCOUNT);

            assertEquals(1, signals.size());
            assertNull(signals.get(0).direction());
        }
    }

    @Nested
    class AnalyseNow {

        @BeforeEach
        void givenAHeldSymbol() {
            when(positionMapper.findAllHeldByAccount(ACCOUNT)).thenReturn(List.of(held("AAA", 10)));
        }

        @Test
        void insideTheCooldownServesTheCacheWithNoFauxnanceCall() {
            TradeSignalRow cached = signalRow("AAA", NOW.minusSeconds(3600), NOW.plusSeconds(3600));
            when(tradeSignalMapper.findBySymbol("AAA")).thenReturn(cached);

            SignalResponse response = service.analyse(ACCOUNT, ACCOUNT, "AAA");

            assertEquals("BUY", response.direction());
            assertEquals(false, response.stale());
            verify(candleClient, never()).fetchRawCandles(anyString());
        }

        @Test
        void pastTheCooldownFetchesRecomputesAndCaches() {
            TradeSignalRow expired = signalRow("AAA", NOW.minusSeconds(100_000), NOW.minusSeconds(1));
            when(tradeSignalMapper.findBySymbol("AAA")).thenReturn(expired);
            when(candleClient.fetchRawCandles("AAA")).thenReturn("{\"data\":{\"candles\":[]}}");
            when(candleClient.parseCloses(any())).thenReturn(List.of());

            // Not enough candles to compute a fresh signal; falls back to the (still useful) old row.
            SignalResponse response = service.analyse(ACCOUNT, ACCOUNT, "AAA");

            verify(candleClient).fetchRawCandles("AAA");
            verify(candleCacheMapper).upsert(eq("AAA"), eq("1d"), any(), any(), any(), any());
            assertEquals("BUY", response.direction());
        }

        @Test
        void aFailedRefreshFallsBackToTheStaleCachedSignal() {
            TradeSignalRow expired = signalRow("AAA", NOW.minusSeconds(100_000), NOW.minusSeconds(1));
            when(tradeSignalMapper.findBySymbol("AAA")).thenReturn(expired);
            when(candleClient.fetchRawCandles("AAA")).thenReturn(null);

            SignalResponse response = service.analyse(ACCOUNT, ACCOUNT, "AAA");

            assertEquals("BUY", response.direction());
            assertTrue(response.stale());
            verify(candleCacheMapper, never()).upsert(any(), any(), any(), any(), any(), any());
        }

        @Test
        void aFailedRefreshWithNothingCachedIsUnavailable() {
            when(tradeSignalMapper.findBySymbol("AAA")).thenReturn(null);
            when(candleClient.fetchRawCandles("AAA")).thenReturn(null);

            assertThrows(AdviceUnavailableException.class, () -> service.analyse(ACCOUNT, ACCOUNT, "AAA"));
        }
    }
}
