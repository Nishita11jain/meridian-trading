package com.trading.api.extensions.statements;

import com.trading.domain.exception.AccountNotActiveException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementsServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2027, 3, 31);

    private final StatementsMapper mapper = mock(StatementsMapper.class);
    private final StatementsService service = new StatementsService(mapper);

    private static TradeLine trade(String symbol, String side, long quantity, String price, String currency) {
        TradeLine t = new TradeLine();
        t.setExecutedAt(LocalDateTime.of(2026, 10, 5, 10, 30, 0));
        t.setOrderId("a1b2");
        t.setSymbol(symbol);
        t.setSide(side);
        t.setQuantity(quantity);
        t.setPrice(new BigDecimal(price));
        t.setCurrency(currency);
        return t;
    }

    @Test
    void anotherAccountsStatementIsRefusedWithoutQuerying() {
        assertThrows(AccountNotActiveException.class, () -> service.trades(5L, 4L, FROM, TO));
        verify(mapper, never()).findTrades(any(), any(), any());
    }

    @Test
    void theRangeIsInclusiveOfItsLastDay() {
        when(mapper.findTrades(eq(4L), any(), any())).thenReturn(List.of());

        service.trades(4L, 4L, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5));

        verify(mapper).findTrades(4L, LocalDateTime.of(2026, 10, 5, 0, 0), LocalDateTime.of(2026, 10, 6, 0, 0));
    }

    @Test
    void aBackwardsOrMissingOrOverlongRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.trades(4L, 4L, TO, FROM));
        assertThrows(IllegalArgumentException.class, () -> service.trades(4L, 4L, null, TO));
        assertThrows(IllegalArgumentException.class,
                () -> service.trades(4L, 4L, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)));
        verify(mapper, never()).findTrades(any(), any(), any());
    }

    @Test
    void theTradesCsvHasAHeaderAndOneRowPerFill() {
        when(mapper.findTrades(eq(4L), any(), any())).thenReturn(List.of(
                trade("MSFT", "BUY", 10, "415.20", "USD")));

        String csv = service.tradesCsv(4L, 4L, FROM, TO);

        String[] lines = csv.split("\r\n");
        assertEquals("Date,Time,Order ID,Symbol,Side,Quantity,Price,Currency,Value", lines[0]);
        assertEquals("2026-10-05,10:30:00,a1b2,MSFT,BUY,10,415.20,USD,4152.00", lines[1]);
    }

    @Test
    void aCellThatLooksLikeAFormulaIsMadeTextAndAFieldWithACommaIsQuoted() {
        assertEquals("'=SUM(A1)", CsvWriter.cell("=SUM(A1)"));
        assertEquals("'+1", CsvWriter.cell("+1"));
        assertEquals("'-2", CsvWriter.cell("-2"));
        assertEquals("'@cmd", CsvWriter.cell("@cmd"));
        assertEquals("\"Infosys, Ltd\"", CsvWriter.cell("Infosys, Ltd"));
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.cell("say \"hi\""));
        assertEquals("", CsvWriter.cell(null));
    }
}
