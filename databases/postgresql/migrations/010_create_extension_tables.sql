-- Sprint 10 extensions: the tables the four extension modules own, added as
-- pure additions with no dependency on any Sprint 1-9 code. Nothing before
-- Sprint 10 reads or writes them, and nothing here alters a table an earlier
-- migration created -- the existing schema and its data are untouched.
--
-- Migration 009 already created the market-data and watchlist read models
-- (market_price, price_history, watchlists, watchlist_items, price_alerts).
-- This migration adds the four tables those modules still need:
--
-- CUSTOMER_PREFERENCES is the single owner of a customer's personalisation:
-- the channel notifications should reach them on, the contact detail for that
-- channel, the currency a total is formatted in, and the UI theme. One row
-- per account. A missing row means "defaults not yet set", which the service
-- treats as the documented defaults rather than an error, so no row is seeded
-- here; the first save creates it. default_account_id exists for the Sprint 10
-- acceptance criterion and, on this platform, always equals account_id because
-- a customer maps to exactly one account (auth.users.uq_auth_users_account_id).
--
-- NOTIFICATIONS is the customer-facing inbox plus the delivery-state ledger.
-- source_event_id is the trade-events (or alert) eventId the row was created
-- from, UNIQUE so an at-least-once redelivery of the same event cannot create
-- a second notification. delivery_state tracks queued/sent/failed separately
-- from the Kafka offset, as the brief requires. No contact detail, card
-- number or secret is ever written to subject/message: those are personal
-- data and stay out of the inbox text as they stay out of the Kafka payload.
--
-- PROCESSED_EVENTS is the shared idempotency ledger for the two Sprint 10
-- consumers (notification-service on trade-events, watchlist-service on
-- market-data). (consumer_name, event_id) is the key, so each consumer
-- deduplicates on its own and claiming an event is one guarded INSERT.
--
-- PRICE_ALERT_STATE carries the last price each alert observed, so the
-- watchlist consumer can tell a genuine threshold *crossing* (the price moved
-- through the level this tick) from a price that was already past it. It is a
-- separate table rather than a new column on price_alerts so this migration
-- leaves every table migration 009 created exactly as it was.
--
-- This migration is written to be safely re-runnable: every CREATE and every
-- index is guarded, so running apply.sh again on a database that already has
-- this migration applied is a no-op here, not an error.

CREATE TABLE IF NOT EXISTS customer_preferences (
    account_id            BIGINT        NOT NULL,
    default_account_id    BIGINT        NOT NULL,
    notification_channel  VARCHAR(10)   NOT NULL DEFAULT 'IN_APP',
    contact_email         VARCHAR(320),
    contact_phone         VARCHAR(32),
    display_currency      CHAR(3)       NOT NULL DEFAULT 'INR',
    theme                 VARCHAR(10)   NOT NULL DEFAULT 'SYSTEM',
    updated_on            TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_customer_preferences PRIMARY KEY (account_id),
    CONSTRAINT fk_customer_preferences_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_customer_preferences_default_account
        FOREIGN KEY (default_account_id) REFERENCES accounts (id),
    CONSTRAINT ck_customer_preferences_channel
        CHECK (notification_channel IN ('IN_APP', 'EMAIL', 'SMS', 'PUSH')),
    CONSTRAINT ck_customer_preferences_currency
        CHECK (display_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_customer_preferences_theme
        CHECK (theme IN ('LIGHT', 'DARK', 'SYSTEM'))
);

CREATE TABLE IF NOT EXISTS notifications (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    account_id       BIGINT        NOT NULL,
    source_event_id  UUID          NOT NULL,
    type             VARCHAR(40)   NOT NULL,
    channel          VARCHAR(10)   NOT NULL,
    subject          VARCHAR(160)  NOT NULL,
    message          VARCHAR(1000) NOT NULL,
    delivery_state   VARCHAR(10)   NOT NULL DEFAULT 'SENT',
    read_on          TIMESTAMP,
    created_on       TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT uq_notifications_source_event_id UNIQUE (source_event_id),
    CONSTRAINT fk_notifications_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_notifications_channel
        CHECK (channel IN ('IN_APP', 'EMAIL', 'SMS', 'PUSH')),
    CONSTRAINT ck_notifications_delivery_state
        CHECK (delivery_state IN ('QUEUED', 'SENT', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS ix_notifications_account_created
    ON notifications (account_id, created_on DESC);

CREATE TABLE IF NOT EXISTS processed_events (
    consumer_name  VARCHAR(40)  NOT NULL,
    event_id       UUID         NOT NULL,
    processed_on   TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_processed_events PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE IF NOT EXISTS price_alert_state (
    alert_id             BIGINT        NOT NULL,
    last_observed_price  NUMERIC(18,4),
    updated_on           TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_price_alert_state PRIMARY KEY (alert_id),
    CONSTRAINT fk_price_alert_state_alert
        FOREIGN KEY (alert_id) REFERENCES price_alerts (id) ON DELETE CASCADE
);

COMMENT ON TABLE customer_preferences IS
    'One row per account: notification channel and contact, display currency, theme. Missing row means defaults.';
COMMENT ON TABLE notifications IS
    'Customer inbox and delivery-state ledger. source_event_id is UNIQUE so a redelivered event cannot notify twice.';
COMMENT ON TABLE processed_events IS
    'Idempotency ledger for the Sprint 10 consumers. (consumer_name, event_id) is claimed once per event.';
COMMENT ON TABLE price_alert_state IS
    'Last price each alert observed, so the watchlist consumer fires on a crossing rather than on any price already past the level.';
