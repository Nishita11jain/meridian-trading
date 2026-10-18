# 0025 Dollar orders are converted to rupees once, at placement, and the portfolio is shown in rupees from the same stored daily rate

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-08 |
| Decided by | Team 5 |

## Context

The published portfolio contract has one `baseCurrency` and says holdings in another currency must be converted before they are summed, or the team must restrict the instrument universe to one currency. We hold rupee stocks and dollar stocks, and cutting the dollar ones would remove most of what customers can trade.

## Options considered

| Option | For | Against |
|---|---|---|
| Restrict the universe to one currency | Smallest change | Drops US stocks and crypto |
| Convert on the order path (lock a rate on each order, reserve and settle in rupees) | Cash and portfolio both correct | Touches placement, settlement and the trades table |
| Convert only when the portfolio is shown, using a stored daily rate | Orders and cash stay as they are, and the contract is met | A dollar fill is still booked in cash at its dollar price, so cash is wrong |
| Convert on the order path and also show the portfolio in rupees | Cash and portfolio both correct, from one stored rate | Touches placement, settlement and the trades table |

## Decision

Do both, from one stored rate. The executor's `FxRatePoller` fetches `FX:USDINR` and `FX:INRUSD` once a day (Fauxnance only updates them daily) in one batched call and stores one row per pair per day in `fx_rates`. The portfolio reads the latest USD to INR rate and converts each dollar holding's cost basis, market value and profit or loss, and the realised profit from sales, at that rate. The base currency is INR. A holding in a currency we have no rate for is left out of the totals and the summary is marked `partial`.

On the order path, placement locks a rate on a dollar order: the stored mid rate moved by `fx.margin-percent` (0.5 by default) against the customer, up for a buy and down for a sell, so the platform keeps the spread. The funds check and the reservation of cash are in rupees at that rate (`orders.fx_rate`). The executor settles at the same rate, so the cash movement is the dollar fill times the locked rate, rounded once to two decimals, and `trades` keeps the dollar price and the rate. With no stored rate a dollar order is refused with `MKT-503`. The order ticket reads the rates from `GET /api/v1/market/fx-rates` and shows the rupee cost before the customer confirms.

## Consequences

- Profit and loss on a dollar holding moves with the exchange rate as well as the price, because both cost and value use today's rate. A rate locked at purchase would separate the two.
- Cash moves in rupees at the locked rate, so a dollar fill no longer takes dollars-as-rupees out of the balance. Checked live on 8 October: buying 1 ETH at 2,562.77 dollars with the rate locked at 96.93225 reduced cash by 2,48,415.06.
- Orders placed before this change have no rate and still settle as rupees.
- Positions stay in each instrument's own currency, so their average cost is the dollar price. The portfolio converts that cost at today's rate, so profit and loss includes the exchange-rate move since purchase. Converting each fill at its own rate would separate the two, and would need the position to carry a rupee cost.
- A sell is credited at the sell rate and a buy charged at the buy rate, so buying and selling the same dollar amount at the same price loses the margin twice.
