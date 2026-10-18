-- Sprint 10: a watchlist item carries a colour flag, not a group.
--
-- Migration 015 added group_name for groups inside a watchlist. Groups were replaced by a lighter
-- design (as TradingView does): a colour flag on a row, RED, AMBER or GREEN, to mark what needs
-- attention. One value per item either way, so the column is renamed rather than replaced.
--
-- flag  RED, AMBER or GREEN, or NULL for none. No CHECK constraint is added, so any value written
--       while the column held group names stays untouched; the API reads anything else as no flag.
--
-- No row changes. Safely re-runnable: the rename only happens while the old column exists.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'watchlist_items' AND column_name = 'group_name') THEN
        ALTER TABLE watchlist_items RENAME COLUMN group_name TO flag;
    END IF;
END $$;

ALTER TABLE watchlist_items ADD COLUMN IF NOT EXISTS flag VARCHAR(50);

COMMENT ON COLUMN watchlist_items.flag IS
    'A colour flag on the instrument in its watchlist: RED, AMBER or GREEN; NULL (or anything else) for none.';
