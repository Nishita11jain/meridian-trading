-- Sprint 10: groups inside a watchlist.
--
-- A customer can sort the instruments of one watchlist into named groups (e.g. "Banks", "IT"), as
-- broker market-watch screens do. The group is a property of the item, so this is one column on
-- watchlist_items rather than a table: a group exists while it has instruments in it.
--
-- group_name  the group the instrument is in, or NULL for none. Nullable and not back-filled: every
--             existing row stays ungrouped, so no existing row changes.
--
-- Safely re-runnable.

ALTER TABLE watchlist_items ADD COLUMN IF NOT EXISTS group_name VARCHAR(50);

COMMENT ON COLUMN watchlist_items.group_name IS
    'The group the instrument is in within its watchlist (e.g. Banks); NULL for none.';
