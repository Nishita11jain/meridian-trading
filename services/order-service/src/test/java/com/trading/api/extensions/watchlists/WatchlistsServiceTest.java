package com.trading.api.extensions.watchlists;

import com.trading.api.mapper.InstrumentMapper;
import com.trading.domain.Instrument;
import com.trading.domain.exception.InstrumentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistsServiceTest {

    private static final long ACCOUNT = 4L;
    private static final long OTHER = 5L;

    private WatchlistsMapper watchlistsMapper;
    private AlertsMapper alertsMapper;
    private MarketDataMapper marketDataMapper;
    private InstrumentMapper instrumentMapper;
    private WatchlistsService service;

    @BeforeEach
    void setUp() {
        watchlistsMapper = mock(WatchlistsMapper.class);
        alertsMapper = mock(AlertsMapper.class);
        marketDataMapper = mock(MarketDataMapper.class);
        instrumentMapper = mock(InstrumentMapper.class);
        when(marketDataMapper.findAll()).thenReturn(List.of());
        when(instrumentMapper.findBySymbol("TCS.NS")).thenReturn(new Instrument("TCS.NS", "Tata", "EQUITY", "INR", true));
        when(instrumentMapper.findBySymbol("OLDTECH.BO")).thenReturn(new Instrument("OLDTECH.BO", "Old", "EQUITY", "INR", false));
        service = new WatchlistsService(watchlistsMapper, alertsMapper, marketDataMapper, instrumentMapper);
    }

    private static WatchlistRow watchlist(long id, long accountId) {
        WatchlistRow row = new WatchlistRow();
        row.setId(id);
        row.setAccountId(accountId);
        row.setName("Tech");
        row.setCreatedOn(Instant.parse("2026-10-01T00:00:00Z"));
        return row;
    }

    // ---- watchlists

    @Test
    void creatingAWatchlistWithADuplicateNameIsRefused() {
        when(watchlistsMapper.nameExistsForAccount(ACCOUNT, "Tech")).thenReturn(true);

        WatchlistConflictException conflict = assertThrows(WatchlistConflictException.class,
                () -> service.createWatchlist(ACCOUNT, new CreateWatchlistRequest("Tech")));

        assertEquals("WL-409", conflict.code());
        verify(watchlistsMapper, never()).insertWatchlist(any());
    }

    @Test
    void creatingAWatchlistInsertsAndReturnsIt() {
        doAnswer(invocation -> { invocation.getArgument(0, WatchlistRow.class).setId(11L); return 1; })
                .when(watchlistsMapper).insertWatchlist(any());
        when(watchlistsMapper.findById(11L)).thenReturn(watchlist(11L, ACCOUNT));
        when(watchlistsMapper.findItemSymbols(11L)).thenReturn(List.of());

        WatchlistResponse response = service.createWatchlist(ACCOUNT, new CreateWatchlistRequest("Tech"));

        assertEquals(11L, response.id());
    }

    @Test
    void readingAnotherAccountsWatchlistIs404() {
        when(watchlistsMapper.findById(99L)).thenReturn(watchlist(99L, OTHER));

        assertThrows(WatchlistNotFoundException.class, () -> service.getWatchlist(ACCOUNT, 99L));
    }

    @Test
    void addingAnUntradableInstrumentIsRefused() {
        when(watchlistsMapper.findById(11L)).thenReturn(watchlist(11L, ACCOUNT));

        assertThrows(InstrumentNotFoundException.class,
                () -> service.addInstrument(ACCOUNT, 11L, new AddInstrumentRequest("OLDTECH.BO")));
        verify(watchlistsMapper, never()).addItem(anyLong(), any());
    }

    @Test
    void addingADuplicateInstrumentIsRefused() {
        when(watchlistsMapper.findById(11L)).thenReturn(watchlist(11L, ACCOUNT));
        when(watchlistsMapper.itemExists(11L, "TCS.NS")).thenReturn(true);

        assertThrows(WatchlistConflictException.class,
                () -> service.addInstrument(ACCOUNT, 11L, new AddInstrumentRequest("tcs.ns")));
    }

    @Test
    void addingAValidInstrumentStoresTheUppercasedSymbol() {
        when(watchlistsMapper.findById(11L)).thenReturn(watchlist(11L, ACCOUNT));
        when(watchlistsMapper.itemExists(11L, "TCS.NS")).thenReturn(false);
        when(watchlistsMapper.findItemSymbols(11L)).thenReturn(List.of("TCS.NS"));

        service.addInstrument(ACCOUNT, 11L, new AddInstrumentRequest("tcs.ns"));

        verify(watchlistsMapper).addItem(11L, "TCS.NS");
    }

    // ---- alerts

    @Test
    void creatingAnAlertAboveTheCapIsRefused() {
        when(alertsMapper.countActiveByAccount(ACCOUNT)).thenReturn(WatchlistsService.MAX_ACTIVE_ALERTS_PER_ACCOUNT);

        AlertLimitException limit = assertThrows(AlertLimitException.class,
                () -> service.createAlert(ACCOUNT, new CreateAlertRequest("TCS.NS", "ABOVE", new BigDecimal("3500"))));

        assertEquals("LIMIT-429", limit.code());
        verify(alertsMapper, never()).insertAlert(any());
    }

    @Test
    void creatingAnAlertOnAnUntradableInstrumentIsRefused() {
        assertThrows(InstrumentNotFoundException.class,
                () -> service.createAlert(ACCOUNT, new CreateAlertRequest("OLDTECH.BO", "ABOVE", new BigDecimal("1"))));
    }

    @Test
    void creatingAnAlertInsertsAndReturnsIt() {
        when(alertsMapper.countActiveByAccount(ACCOUNT)).thenReturn(0);
        doAnswer(invocation -> { invocation.getArgument(0, AlertRow.class).setId(21L); return 1; })
                .when(alertsMapper).insertAlert(any());
        AlertRow stored = new AlertRow();
        stored.setId(21L); stored.setAccountId(ACCOUNT); stored.setSymbol("TCS.NS");
        stored.setDirection("ABOVE"); stored.setTargetPrice(new BigDecimal("3500")); stored.setStatus("ACTIVE");
        when(alertsMapper.findById(21L)).thenReturn(stored);

        AlertResponse response = service.createAlert(ACCOUNT, new CreateAlertRequest("tcs.ns", "ABOVE", new BigDecimal("3500")));

        assertEquals(21L, response.id());
        assertEquals("ACTIVE", response.status());
        verify(alertsMapper).insertAlert(any());
    }

    @Test
    void cancellingAnotherAccountsAlertIs404() {
        AlertRow other = new AlertRow();
        other.setId(21L); other.setAccountId(OTHER);
        when(alertsMapper.findById(21L)).thenReturn(other);

        assertThrows(AlertNotFoundException.class, () -> service.cancelAlert(ACCOUNT, 21L));
        verify(alertsMapper, never()).cancel(anyLong(), anyLong());
    }

    @Test
    void cancellingOwnAlertCallsCancel() {
        AlertRow own = new AlertRow();
        own.setId(21L); own.setAccountId(ACCOUNT); own.setSymbol("TCS.NS");
        own.setDirection("ABOVE"); own.setTargetPrice(new BigDecimal("3500")); own.setStatus("ACTIVE");
        when(alertsMapper.findById(21L)).thenReturn(own);

        service.cancelAlert(ACCOUNT, 21L);

        verify(alertsMapper).cancel(21L, ACCOUNT);
    }

    @Test
    void theAlertLookupReturnsOwnAlertsOnly() {
        AlertRow own = new AlertRow();
        own.setId(21L); own.setAccountId(ACCOUNT); own.setSymbol("TCS.NS");
        own.setDirection("ABOVE"); own.setTargetPrice(new BigDecimal("3500")); own.setStatus("TRIGGERED");
        own.setTriggeredPrice(new BigDecimal("3510.25"));
        AlertRow other = new AlertRow();
        other.setId(22L); other.setAccountId(OTHER);
        when(alertsMapper.findById(21L)).thenReturn(own);
        when(alertsMapper.findById(22L)).thenReturn(other);

        var found = service.findAlert(ACCOUNT, 21L).orElseThrow();
        assertEquals("TRIGGERED", found.status());
        assertEquals(new BigDecimal("3510.25"), found.triggeredPrice());
        assertTrue(service.findAlert(ACCOUNT, 22L).isEmpty(), "another account's alert is not disclosed");
        assertTrue(service.findAlert(ACCOUNT, 99L).isEmpty(), "a missing alert is empty, not an error");
    }

    // ---------------------------------------------------------------- rename, delete, reorder

    private WatchlistRow owned(long id, String name) {
        WatchlistRow row = new WatchlistRow();
        row.setId(id); row.setAccountId(ACCOUNT); row.setName(name);
        when(watchlistsMapper.findById(id)).thenReturn(row);
        return row;
    }

    @Test
    void renamingToAFreeNameRenames() {
        owned(11L, "Tech");
        when(watchlistsMapper.nameExistsForAccount(ACCOUNT, "Banks")).thenReturn(false);

        service.renameWatchlist(ACCOUNT, 11L, new RenameWatchlistRequest("  Banks "));

        verify(watchlistsMapper).renameWatchlist(11L, "Banks");
    }

    @Test
    void renamingToAnotherListsNameIsAConflict() {
        owned(11L, "Tech");
        when(watchlistsMapper.nameExistsForAccount(ACCOUNT, "Banks")).thenReturn(true);

        assertThrows(WatchlistConflictException.class,
                () -> service.renameWatchlist(ACCOUNT, 11L, new RenameWatchlistRequest("Banks")));
        verify(watchlistsMapper, never()).renameWatchlist(anyLong(), any());
    }

    @Test
    void renamingToItsOwnNameIsAllowed() {
        owned(11L, "Tech");

        service.renameWatchlist(ACCOUNT, 11L, new RenameWatchlistRequest("Tech"));

        verify(watchlistsMapper).renameWatchlist(11L, "Tech");
    }

    @Test
    void deletingRemovesTheItemsThenTheList() {
        owned(11L, "Tech");

        service.deleteWatchlist(ACCOUNT, 11L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(watchlistsMapper);
        order.verify(watchlistsMapper).deleteItems(11L);
        order.verify(watchlistsMapper).deleteWatchlist(11L);
    }

    @Test
    void anotherAccountsListCannotBeRenamedDeletedOrReordered() {
        WatchlistRow other = new WatchlistRow();
        other.setId(12L); other.setAccountId(OTHER); other.setName("Theirs");
        when(watchlistsMapper.findById(12L)).thenReturn(other);

        assertThrows(WatchlistNotFoundException.class, () -> service.renameWatchlist(ACCOUNT, 12L, new RenameWatchlistRequest("Mine")));
        assertThrows(WatchlistNotFoundException.class, () -> service.deleteWatchlist(ACCOUNT, 12L));
        assertThrows(WatchlistNotFoundException.class, () -> service.reorderInstruments(ACCOUNT, 12L, new ReorderWatchlistRequest(List.of())));
        verify(watchlistsMapper, never()).deleteWatchlist(anyLong());
    }

    @Test
    void reorderingPutsTheNamedSymbolsFirstAndKeepsTheRestAfterThem() {
        owned(11L, "Tech");
        when(watchlistsMapper.findItemSymbols(11L)).thenReturn(List.of("ICICIBANK.NS", "MSFT", "TCS.NS"));

        service.reorderInstruments(ACCOUNT, 11L, new ReorderWatchlistRequest(List.of("tcs.ns", "NOT.ON.LIST", "MSFT")));

        verify(watchlistsMapper).setItemPosition(11L, "TCS.NS", 0);
        verify(watchlistsMapper).setItemPosition(11L, "MSFT", 1);
        verify(watchlistsMapper).setItemPosition(11L, "ICICIBANK.NS", 2);
        verify(watchlistsMapper, never()).setItemPosition(eq(11L), eq("NOT.ON.LIST"), org.mockito.ArgumentMatchers.anyInt());
    }

    // ---------------------------------------------------------------- groups and ready-made lists

    @Test
    void anInstrumentCanBeFlaggedAndUnflagged() {
        owned(11L, "Tech");
        when(watchlistsMapper.itemExists(11L, "TCS.NS")).thenReturn(true);

        service.setItemFlag(ACCOUNT, 11L, "tcs.ns", new SetItemFlagRequest(" green "));
        service.setItemFlag(ACCOUNT, 11L, "TCS.NS", new SetItemFlagRequest(null));

        verify(watchlistsMapper).setItemFlag(11L, "TCS.NS", "GREEN");
        verify(watchlistsMapper).setItemFlag(11L, "TCS.NS", null);
    }

    @Test
    void flaggingAnInstrumentThatIsNotOnTheListIsNotFound() {
        owned(11L, "Tech");
        when(watchlistsMapper.itemExists(11L, "MSFT")).thenReturn(false);

        assertThrows(WatchlistNotFoundException.class,
                () -> service.setItemFlag(ACCOUNT, 11L, "MSFT", new SetItemFlagRequest("RED")));
        verify(watchlistsMapper, never()).setItemFlag(anyLong(), any(), any());
    }

    @Test
    void aValueFromBeforeFlagsReadsAsNoFlag() {
        owned(11L, "Tech");
        WatchlistItemRow old = new WatchlistItemRow();
        old.setSymbol("TCS.NS");
        old.setFlag("bse"); // written while the column held group names
        WatchlistItemRow flagged = new WatchlistItemRow();
        flagged.setSymbol("MSFT");
        flagged.setFlag("amber");
        when(watchlistsMapper.findItems(11L)).thenReturn(List.of(old, flagged));

        var items = service.getWatchlist(ACCOUNT, 11L).items();

        assertEquals(null, items.get(0).flag());
        assertEquals("AMBER", items.get(1).flag());
    }

    @Test
    void readyMadeListsAreOnePerMarketFromTheCatalogue() {
        when(instrumentMapper.findAllTradable()).thenReturn(List.of(
                new Instrument("TCS.NS", "Tata", "EQUITY", "INR", true),
                new Instrument("MSFT", "Microsoft", "EQUITY", "USD", true),
                new Instrument("ICICIBANK.NS", "ICICI", "EQUITY", "INR", true),
                new Instrument("X:ETH-USD", "Ether", "CRYPTO", "USD", true)));

        var presets = service.presets();

        assertEquals(List.of("NSE", "US", "CRYPTO"), presets.stream().map(WatchlistPresetResponse::key).toList());
        assertEquals("NSE stocks", presets.get(0).name());
        assertEquals(List.of("TCS.NS", "ICICIBANK.NS"), presets.get(0).symbols());
    }

    @Test
    void creatingFromAReadyMadeListTakesTheNextFreeName() {
        when(instrumentMapper.findAllTradable()).thenReturn(List.of(new Instrument("TCS.NS", "Tata", "EQUITY", "INR", true)));
        when(watchlistsMapper.nameExistsForAccount(ACCOUNT, "NSE stocks")).thenReturn(true);
        when(watchlistsMapper.nameExistsForAccount(ACCOUNT, "NSE stocks 2")).thenReturn(false);
        doAnswer(invocation -> { invocation.getArgument(0, WatchlistRow.class).setId(30L); return 1; })
                .when(watchlistsMapper).insertWatchlist(any());
        owned(30L, "NSE stocks 2");

        service.createFromPreset(ACCOUNT, "nse");

        verify(watchlistsMapper).insertWatchlist(org.mockito.ArgumentMatchers.argThat(r -> "NSE stocks 2".equals(r.getName())));
        verify(watchlistsMapper).addItem(30L, "TCS.NS");
    }

    @Test
    void anUnknownReadyMadeListIsNotFound() {
        when(instrumentMapper.findAllTradable()).thenReturn(List.of());

        assertThrows(WatchlistNotFoundException.class, () -> service.createFromPreset(ACCOUNT, "LSE"));
    }
}
