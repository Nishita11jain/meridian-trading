package com.trading.api.extensions.watchlists;

import java.time.Instant;

/** One row of watchlists. */
public class WatchlistRow {
    private Long id;
    private Long accountId;
    private String name;
    private Instant createdOn;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Instant getCreatedOn() { return createdOn; }
    public void setCreatedOn(Instant createdOn) { this.createdOn = createdOn; }
}
