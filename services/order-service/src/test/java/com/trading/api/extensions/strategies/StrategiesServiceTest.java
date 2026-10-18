package com.trading.api.extensions.strategies;

import com.trading.api.mapper.InstrumentMapper;
import com.trading.domain.Instrument;
import com.trading.domain.exception.AccountNotActiveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The strategies CRUD service over stand-in mappers: the cross-account refusal, the strategy cap,
 * the symbol/side/type validation, the CROSSOVER-vs-BOLLINGER required-field split, and that
 * re-enabling a strategy resets its failure count rather than leaving it primed to re-trip.
 */
class StrategiesServiceTest {

    private static final long ACCOUNT = 4L;
    private static final long OTHER = 5L;

    private StrategiesMapper strategiesMapper;
    private StrategyExecutionMapper executionMapper;
    private InstrumentMapper instrumentMapper;
    private StrategiesService service;

    @BeforeEach
    void setUp() {
        strategiesMapper = mock(StrategiesMapper.class);
        executionMapper = mock(StrategyExecutionMapper.class);
        instrumentMapper = mock(InstrumentMapper.class);
        service = new StrategiesService(strategiesMapper, executionMapper, instrumentMapper, 10);

        when(instrumentMapper.findBySymbol("NVDA")).thenReturn(new Instrument("NVDA", "Nvidia", "EQUITY", "USD", true));
        when(strategiesMapper.countForAccount(ACCOUNT)).thenReturn(0);
        // Mirrors MyBatis's useGeneratedKeys + a re-read: insert sets the id, findById returns
        // that same row back (a real mapper would re-select it; the content is identical either way).
        when(strategiesMapper.insert(any())).thenAnswer(inv -> {
            StrategyRow row = inv.getArgument(0);
            row.setId(42L);
            when(strategiesMapper.findById(42L)).thenReturn(row);
            return 1;
        });
        when(strategiesMapper.findById(anyLong())).thenAnswer(inv -> {
            StrategyRow row = new StrategyRow();
            row.setId(inv.getArgument(0));
            row.setAccountId(ACCOUNT);
            return row;
        });
    }

    private static StrategyRequest crossoverRequest() {
        return new StrategyRequest("NVDA", "BUY", "CROSSOVER", 6, 12, null, null,
                20, new BigDecimal("5000"), 100, 3);
    }

    @Nested
    class Ownership {

        @Test
        void listRefusesAMismatchedAccount() {
            assertThrows(AccountNotActiveException.class, () -> service.list(ACCOUNT, OTHER));
        }

        @Test
        void createRefusesAMismatchedAccount() {
            assertThrows(AccountNotActiveException.class, () -> service.create(ACCOUNT, OTHER, crossoverRequest()));
        }
    }

    @Nested
    class Creation {

        @Test
        void theCapRefusesACreateOnceReached() {
            when(strategiesMapper.countForAccount(ACCOUNT)).thenReturn(10);

            assertThrows(StrategyLimitException.class, () -> service.create(ACCOUNT, ACCOUNT, crossoverRequest()));
            verify(strategiesMapper, org.mockito.Mockito.never()).insert(any());
        }

        @Test
        void anUntradableOrUnknownSymbolIsRejected() {
            when(instrumentMapper.findBySymbol("ZZZZ")).thenReturn(null);
            StrategyRequest request = new StrategyRequest("ZZZZ", "BUY", "CROSSOVER", 6, 12, null, null,
                    20, new BigDecimal("5000"), 100, 3);

            assertThrows(InvalidStrategyException.class, () -> service.create(ACCOUNT, ACCOUNT, request));
        }

        @Test
        void crossoverRequiresFastAndSlowWindowWithFastSmaller() {
            StrategyRequest missingWindows = new StrategyRequest("NVDA", "BUY", "CROSSOVER", null, null, null, null,
                    20, new BigDecimal("5000"), 100, 3);
            assertThrows(InvalidStrategyException.class, () -> service.create(ACCOUNT, ACCOUNT, missingWindows));

            StrategyRequest fastNotSmaller = new StrategyRequest("NVDA", "BUY", "CROSSOVER", 12, 6, null, null,
                    20, new BigDecimal("5000"), 100, 3);
            assertThrows(InvalidStrategyException.class, () -> service.create(ACCOUNT, ACCOUNT, fastNotSmaller));
        }

        @Test
        void bollingerRequiresPeriodAndMultiplier() {
            StrategyRequest missing = new StrategyRequest("NVDA", "BUY", "BOLLINGER", null, null, null, null,
                    20, new BigDecimal("5000"), 100, 3);

            assertThrows(InvalidStrategyException.class, () -> service.create(ACCOUNT, ACCOUNT, missing));
        }

        @Test
        void aValidCrossoverRequestIsInserted() {
            StrategyResponse response = service.create(ACCOUNT, ACCOUNT, crossoverRequest());

            verify(strategiesMapper).insert(any());
            assertEquals("NVDA", response.symbol());
        }
    }

    @Nested
    class StatusUpdates {

        @Test
        void reEnablingAStrategyResetsItsFailureCount() {
            StrategyRow disabled = new StrategyRow();
            disabled.setId(9L);
            disabled.setAccountId(ACCOUNT);
            disabled.setStatus("DISABLED");
            disabled.setConsecutiveFailures(3);
            when(strategiesMapper.findById(9L)).thenReturn(disabled);

            service.updateStatus(ACCOUNT, ACCOUNT, 9L, new UpdateStrategyStatusRequest("ACTIVE"));

            verify(strategiesMapper).updateStatus(eq(9L), eq("ACTIVE"), eq(null));
            verify(strategiesMapper).resetFailures(9L);
        }

        @Test
        void aStrategyNotOwnedByTheAccountIsNotFound() {
            StrategyRow someoneElses = new StrategyRow();
            someoneElses.setId(9L);
            someoneElses.setAccountId(OTHER);
            when(strategiesMapper.findById(9L)).thenReturn(someoneElses);

            assertThrows(StrategyNotFoundException.class,
                    () -> service.updateStatus(ACCOUNT, ACCOUNT, 9L, new UpdateStrategyStatusRequest("DISABLED")));
        }
    }

    @Test
    void listReturnsEveryStrategyForTheAccount() {
        StrategyRow a = new StrategyRow();
        a.setId(1L);
        a.setAccountId(ACCOUNT);
        a.setSymbol("NVDA");
        when(strategiesMapper.findByAccount(ACCOUNT)).thenReturn(List.of(a));

        assertEquals(1, service.list(ACCOUNT, ACCOUNT).size());
    }
}
