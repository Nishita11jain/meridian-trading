package com.trading.api.extensions.watchlists;

import com.trading.api.extensions.notifications.AlertDetails;
import com.trading.api.extensions.notifications.AlertLookup;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.domain.Instrument;
import com.trading.domain.exception.InstrumentNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Watchlists and price alerts a customer owns. Everything filters on the account from the verified
 * token: a list, watchlist or alert belonging to another account is answered 404 (WL-404 / ALERT-404),
 * never returned, so ownership is neither disclosed nor bypassable with an id from the client.
 */
@Service
public class WatchlistsService implements AlertLookup {

    /** A cap so one account cannot fill the table and overload the market-data consumer. */
    static final int MAX_ACTIVE_ALERTS_PER_ACCOUNT = 50;

    private final WatchlistsMapper watchlistsMapper;
    private final AlertsMapper alertsMapper;
    private final MarketDataMapper marketDataMapper;
    private final InstrumentMapper instrumentMapper;

    public WatchlistsService(WatchlistsMapper watchlistsMapper, AlertsMapper alertsMapper,
                             MarketDataMapper marketDataMapper, InstrumentMapper instrumentMapper) {
        this.watchlistsMapper = watchlistsMapper;
        this.alertsMapper = alertsMapper;
        this.marketDataMapper = marketDataMapper;
        this.instrumentMapper = instrumentMapper;
    }

    // ---------------------------------------------------------------- watchlists

    @Transactional
    public WatchlistResponse createWatchlist(long accountId, CreateWatchlistRequest request) {
        String name = request.name().trim();
        if (watchlistsMapper.nameExistsForAccount(accountId, name)) {
            throw new WatchlistConflictException("a watchlist with that name already exists");
        }
        WatchlistRow row = new WatchlistRow();
        row.setAccountId(accountId);
        row.setName(name);
        watchlistsMapper.insertWatchlist(row);
        return toResponse(watchlistsMapper.findById(row.getId()), quotesBySymbol());
    }

    public List<WatchlistResponse> listWatchlists(long accountId) {
        Map<String, MarketQuoteResponse> quotes = quotesBySymbol();
        return watchlistsMapper.findByAccount(accountId).stream()
                .map(row -> toResponse(row, quotes))
                .toList();
    }

    public WatchlistResponse getWatchlist(long accountId, long watchlistId) {
        return toResponse(requireOwnedWatchlist(accountId, watchlistId), quotesBySymbol());
    }

    @Transactional
    public WatchlistResponse addInstrument(long accountId, long watchlistId, AddInstrumentRequest request) {
        WatchlistRow watchlist = requireOwnedWatchlist(accountId, watchlistId);
        String symbol = requireTradableSymbol(request.symbol());
        if (watchlistsMapper.itemExists(watchlistId, symbol)) {
            throw new WatchlistConflictException("that instrument is already on the watchlist");
        }
        watchlistsMapper.addItem(watchlistId, symbol);
        return toResponse(watchlist, quotesBySymbol());
    }

    @Transactional
    public WatchlistResponse removeInstrument(long accountId, long watchlistId, String symbol) {
        WatchlistRow watchlist = requireOwnedWatchlist(accountId, watchlistId);
        watchlistsMapper.removeItem(watchlistId, symbol.trim().toUpperCase());
        return toResponse(watchlist, quotesBySymbol());
    }

    @Transactional
    public WatchlistResponse renameWatchlist(long accountId, long watchlistId, RenameWatchlistRequest request) {
        WatchlistRow watchlist = requireOwnedWatchlist(accountId, watchlistId);
        String name = request.name().trim();
        if (!name.equals(watchlist.getName()) && watchlistsMapper.nameExistsForAccount(accountId, name)) {
            throw new WatchlistConflictException("a watchlist with that name already exists");
        }
        watchlistsMapper.renameWatchlist(watchlistId, name);
        return toResponse(watchlistsMapper.findById(watchlistId), quotesBySymbol());
    }

    /** Deletes the watchlist and its items. Alerts are separate and are not touched. */
    @Transactional
    public void deleteWatchlist(long accountId, long watchlistId) {
        requireOwnedWatchlist(accountId, watchlistId);
        watchlistsMapper.deleteItems(watchlistId);
        watchlistsMapper.deleteWatchlist(watchlistId);
    }

    /**
     * Puts the named instruments first, in the order given; any not named follow in their current order.
     * Symbols that are not on the list are ignored rather than refused, so a stale page cannot fail it.
     */
    @Transactional
    public WatchlistResponse reorderInstruments(long accountId, long watchlistId, ReorderWatchlistRequest request) {
        WatchlistRow watchlist = requireOwnedWatchlist(accountId, watchlistId);
        List<String> current = watchlistsMapper.findItemSymbols(watchlistId);
        java.util.LinkedHashSet<String> order = new java.util.LinkedHashSet<>();
        for (String raw : request.symbols()) {
            String symbol = raw == null ? "" : raw.trim().toUpperCase();
            if (current.contains(symbol)) {
                order.add(symbol);
            }
        }
        order.addAll(current);
        int position = 0;
        for (String symbol : order) {
            watchlistsMapper.setItemPosition(watchlistId, symbol, position++);
        }
        return toResponse(watchlist, quotesBySymbol());
    }

    /** Puts a colour flag (RED, AMBER or GREEN) on an instrument in the list; blank or null removes it. */
    @Transactional
    public WatchlistResponse setItemFlag(long accountId, long watchlistId, String symbol, SetItemFlagRequest request) {
        WatchlistRow watchlist = requireOwnedWatchlist(accountId, watchlistId);
        String item = symbol == null ? "" : symbol.trim().toUpperCase();
        if (!watchlistsMapper.itemExists(watchlistId, item)) {
            throw new WatchlistNotFoundException("that instrument is not on the watchlist");
        }
        watchlistsMapper.setItemFlag(watchlistId, item, knownFlag(request.flag()));
        return toResponse(watchlist, quotesBySymbol());
    }

    /** A flag colour, upper-cased; anything else (blank, or a value from before flags) is no flag. */
    private static String knownFlag(String value) {
        String flag = value == null ? "" : value.trim().toUpperCase();
        return SetItemFlagRequest.COLOURS.contains(flag) ? flag : null;
    }

    // ---------------------------------------------------------------- ready-made lists

    /** One ready-made list per market that has tradable instruments, from the catalogue. */
    public List<WatchlistPresetResponse> presets() {
        Map<com.trading.api.service.TradingMarket, List<String>> byMarket = new java.util.EnumMap<>(com.trading.api.service.TradingMarket.class);
        for (Instrument instrument : instrumentMapper.findAllTradable()) {
            byMarket.computeIfAbsent(com.trading.api.service.TradingMarket.forSymbol(instrument.getSymbol()), m -> new java.util.ArrayList<>())
                    .add(instrument.getSymbol());
        }
        return byMarket.entrySet().stream()
                .map(e -> new WatchlistPresetResponse(e.getKey().name(), presetName(e.getKey()), e.getValue()))
                .toList();
    }

    /**
     * Creates the customer's own watchlist from a ready-made list, named after it ("NSE stocks", or
     * "NSE stocks 2" if that name is taken), with the market's instruments in catalogue order.
     */
    @Transactional
    public WatchlistResponse createFromPreset(long accountId, String key) {
        WatchlistPresetResponse preset = presets().stream()
                .filter(p -> p.key().equalsIgnoreCase(key == null ? "" : key.trim()))
                .findFirst()
                .orElseThrow(() -> new WatchlistNotFoundException("no such ready-made list"));
        String name = preset.name();
        for (int n = 2; watchlistsMapper.nameExistsForAccount(accountId, name); n++) {
            name = preset.name() + " " + n;
        }
        WatchlistRow row = new WatchlistRow();
        row.setAccountId(accountId);
        row.setName(name);
        watchlistsMapper.insertWatchlist(row);
        for (String symbol : preset.symbols()) {
            watchlistsMapper.addItem(row.getId(), symbol);
        }
        return toResponse(watchlistsMapper.findById(row.getId()), quotesBySymbol());
    }

    private static String presetName(com.trading.api.service.TradingMarket market) {
        return switch (market) {
            case NSE -> "NSE stocks";
            case BSE -> "BSE stocks";
            case US -> "US stocks";
            case FX -> "Currencies (FX)";
            case CRYPTO -> "Crypto";
        };
    }

    // ---------------------------------------------------------------- alerts

    @Transactional
    public AlertResponse createAlert(long accountId, CreateAlertRequest request) {
        String symbol = requireTradableSymbol(request.symbol());
        if (alertsMapper.countActiveByAccount(accountId) >= MAX_ACTIVE_ALERTS_PER_ACCOUNT) {
            throw new AlertLimitException("you already have the maximum number of active alerts");
        }
        AlertRow row = new AlertRow();
        row.setAccountId(accountId);
        row.setSymbol(symbol);
        row.setDirection(request.direction());
        row.setTargetPrice(request.targetPrice());
        alertsMapper.insertAlert(row);
        return toResponse(alertsMapper.findById(row.getId()));
    }

    public List<AlertResponse> listAlerts(long accountId) {
        return alertsMapper.findByAccount(accountId).stream().map(WatchlistsService::toResponse).toList();
    }

    @Transactional
    public AlertResponse cancelAlert(long accountId, long alertId) {
        AlertRow alert = alertsMapper.findById(alertId);
        if (alert == null || !alert.getAccountId().equals(accountId)) {
            throw new AlertNotFoundException("alert not found");
        }
        alertsMapper.cancel(alertId, accountId);
        return toResponse(alertsMapper.findById(alertId));
    }

    /** The notifications module reads an alert through this, never through the alerts table. */
    @Override
    public Optional<AlertDetails> findAlert(long accountId, long alertId) {
        AlertRow row = alertsMapper.findById(alertId);
        if (row == null || !row.getAccountId().equals(accountId)) {
            return Optional.empty();
        }
        return Optional.of(new AlertDetails(row.getId(), row.getSymbol(), row.getDirection(), row.getTargetPrice(),
                row.getStatus(), row.getTriggeredOn(), row.getTriggeredPrice()));
    }

    // ---------------------------------------------------------------- helpers

    private WatchlistRow requireOwnedWatchlist(long accountId, long watchlistId) {
        WatchlistRow row = watchlistsMapper.findById(watchlistId);
        if (row == null || !row.getAccountId().equals(accountId)) {
            throw new WatchlistNotFoundException("watchlist not found");
        }
        return row;
    }

    private String requireTradableSymbol(String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase();
        Instrument instrument = instrumentMapper.findBySymbol(symbol);
        if (instrument == null || !instrument.isTradable()) {
            throw new InstrumentNotFoundException("that instrument cannot be traded");
        }
        return symbol;
    }

    private Map<String, MarketQuoteResponse> quotesBySymbol() {
        Map<String, MarketQuoteResponse> quotes = new HashMap<>();
        for (MarketPriceRow row : marketDataMapper.findAll()) {
            quotes.put(row.getSymbol(), MarketDataService.toResponse(row));
        }
        return quotes;
    }

    private WatchlistResponse toResponse(WatchlistRow row, Map<String, MarketQuoteResponse> quotes) {
        List<WatchlistItemResponse> items = watchlistsMapper.findItems(row.getId()).stream()
                .map(item -> new WatchlistItemResponse(item.getSymbol(), quotes.get(item.getSymbol()), knownFlag(item.getFlag())))
                .toList();
        return new WatchlistResponse(row.getId(), row.getName(), row.getCreatedOn(), items);
    }

    private static AlertResponse toResponse(AlertRow row) {
        return new AlertResponse(row.getId(), row.getSymbol(), row.getDirection(), row.getTargetPrice(),
                row.getStatus(), row.getCreatedOn(), row.getTriggeredOn(), row.getTriggeredPrice());
    }
}
