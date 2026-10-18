package com.trading.api.extensions.statements;

import com.trading.domain.exception.AccountNotActiveException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Trades and cash for one account over a date range, and their CSV. Ownership is checked the same way as the
 * portfolio: a path account other than the token's own is refused with ACC-403.
 */
@Service
public class StatementsService {

    /** The longest range one statement covers. A bigger request is refused rather than silently cut short. */
    static final int MAX_DAYS = 400;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final StatementsMapper mapper;

    public StatementsService(StatementsMapper mapper) {
        this.mapper = mapper;
    }

    public List<TradeLine> trades(Long accountId, Long tokenAccountId, LocalDate from, LocalDate to) {
        requireOwnership(accountId, tokenAccountId);
        requireRange(from, to);
        return mapper.findTrades(accountId, start(from), end(to));
    }

    public List<CashLine> cash(Long accountId, Long tokenAccountId, LocalDate from, LocalDate to) {
        requireOwnership(accountId, tokenAccountId);
        requireRange(from, to);
        return mapper.findCash(accountId, start(from), end(to));
    }

    /** The trades as CSV, one row per fill, with a header row. */
    public String tradesCsv(Long accountId, Long tokenAccountId, LocalDate from, LocalDate to) {
        List<String> out = new ArrayList<>();
        out.add(CsvWriter.row(List.of("Date", "Time", "Order ID", "Symbol", "Side", "Quantity", "Price",
                "Currency", "Value")));
        for (TradeLine t : trades(accountId, tokenAccountId, from, to)) {
            out.add(CsvWriter.row(List.of(
                    t.getExecutedAt().format(DAY),
                    t.getExecutedAt().format(TIME),
                    t.getOrderId(),
                    t.getSymbol(),
                    t.getSide(),
                    String.valueOf(t.getQuantity()),
                    t.getPrice().toPlainString(),
                    t.getCurrency(),
                    t.getValue().toPlainString())));
        }
        return String.join("", out);
    }

    /** The cash movements as CSV. Money in is positive, money out is negative. */
    public String cashCsv(Long accountId, Long tokenAccountId, LocalDate from, LocalDate to) {
        List<String> out = new ArrayList<>();
        out.add(CsvWriter.row(List.of("Date", "Time", "Type", "Amount", "Reference")));
        for (CashLine c : cash(accountId, tokenAccountId, from, to)) {
            out.add(CsvWriter.row(List.of(
                    c.getCreatedAt().format(DAY),
                    c.getCreatedAt().format(TIME),
                    c.getType(),
                    c.getAmount().toPlainString(),
                    c.getReference())));
        }
        return String.join("", out);
    }

    /** Refuses a range that is missing, backwards, or longer than {@link #MAX_DAYS}, before any query runs. */
    static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new IllegalArgumentException("a statement covers at most " + MAX_DAYS + " days");
        }
    }

    private static LocalDateTime start(LocalDate from) {
        return from.atStartOfDay();
    }

    /** The range includes its last day, so the end is the start of the day after. */
    private static LocalDateTime end(LocalDate to) {
        return to.plusDays(1).atStartOfDay();
    }

    private static void requireOwnership(Long accountId, Long tokenAccountId) {
        if (accountId == null || !accountId.equals(tokenAccountId)) {
            throw new AccountNotActiveException("account is not active");
        }
    }
}
