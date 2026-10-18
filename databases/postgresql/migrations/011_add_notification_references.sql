-- Sprint 10: what a notification is about.
--
-- A notification so far recorded only its own text (subject, message), its type, the channel it was
-- routed on and its delivery state -- not the order or the price alert it came from. The inbox detail
-- view needs that link, to show the order's side, quantity, prices, status and rejection reason, or
-- the alert's threshold and the price that triggered it, and to link through to them.
--
-- These are plain reference columns, deliberately without foreign keys. The notifications module owns
-- this table; the orders and alerts belong to other modules. A foreign key would bind this module's
-- table to theirs at the schema level, which is the cross-module reach the Sprint 10 brief asks each
-- module not to make. The detail view resolves a reference through the owning module's service layer,
-- and treats a reference it cannot resolve as "details no longer available", not as an error.
--
-- order_id  the order a trade-events notification (filled, rejected, cancelled) is about
-- alert_id  the price alert a PRICE_ALERT_TRIGGERED notification is about
-- symbol    the instrument either is about, kept so the inbox can show it without a lookup
--
-- All three are nullable and nothing is back-filled: notifications recorded before this migration
-- simply have no reference, and the detail view shows only their own text. No existing row changes.
--
-- Safely re-runnable: every ADD COLUMN and index is guarded.

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS order_id UUID;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS alert_id BIGINT;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS symbol VARCHAR(20);

COMMENT ON COLUMN notifications.order_id IS
    'The order a trade-events notification is about. No foreign key: orders belong to another module.';
COMMENT ON COLUMN notifications.alert_id IS
    'The price alert a PRICE_ALERT_TRIGGERED notification is about. No foreign key: alerts belong to the watchlists module.';
COMMENT ON COLUMN notifications.symbol IS
    'The instrument the notification is about.';
