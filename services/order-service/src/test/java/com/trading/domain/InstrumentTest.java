package com.trading.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentTest {

    @Test
    void delistAndRelistToggleTradableState() {
        Instrument instrument = new Instrument("INFY.NS", "Infosys", "EQUITY", "INR", true);

        instrument.delist();
        assertFalse(instrument.isTradable());

        instrument.relist();
        assertTrue(instrument.isTradable());
    }
}