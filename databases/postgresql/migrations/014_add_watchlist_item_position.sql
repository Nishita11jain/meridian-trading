-- Sprint 10: the customer's own order of instruments in a watchlist.
--
-- watchlist_items listed instruments alphabetically. A watchlist is the customer's own arrangement, so
-- they can now drag instruments into the order they want; this column holds it.
--
-- position  0-based place in the list, set when an item is added (at the end) or the list is reordered.
--           Nullable and NOT back-filled: existing rows keep NULL and sort after positioned ones, by
--           when they were added and then by symbol, so no existing row changes.
--
-- Safely re-runnable.

ALTER TABLE watchlist_items ADD COLUMN IF NOT EXISTS position INTEGER;

COMMENT ON COLUMN watchlist_items.position IS
    'The customer''s order of the instrument in the watchlist (0-based). NULL sorts last, by added_on then symbol.';
