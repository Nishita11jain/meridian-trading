package com.trading.api.mapper.row;

import java.time.LocalDate;

/** One row of market_holidays: a market and an exchange-local date it does not trade. */
public class MarketHolidayRow {

    private String market;
    private LocalDate holidayDate;

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public LocalDate getHolidayDate() {
        return holidayDate;
    }

    public void setHolidayDate(LocalDate holidayDate) {
        this.holidayDate = holidayDate;
    }
}
