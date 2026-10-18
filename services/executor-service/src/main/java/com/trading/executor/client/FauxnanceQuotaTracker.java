package com.trading.executor.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FauxnanceQuotaTracker {

    private static final int DAILY_QUOTA = 2000;

    private final AtomicInteger spentToday = new AtomicInteger(0);
    private volatile LocalDate resetDate;
    private final Clock clock;

    @Autowired
    public FauxnanceQuotaTracker() {
        this(Clock.systemUTC());
    }

    FauxnanceQuotaTracker(Clock clock) {
        this.clock = clock;
        this.resetDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    public synchronized boolean tryReserve(int cost) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!today.equals(resetDate)) {
            spentToday.set(0);
            resetDate = today;
        }

        if (spentToday.get() + cost > DAILY_QUOTA) {
            return false;
        }

        spentToday.addAndGet(cost);
        return true;
    }

    public int remainingToday() {
        return DAILY_QUOTA - spentToday.get();
    }

    void setResetDateForTest(LocalDate resetDate) {
        this.resetDate = resetDate;
    }
}