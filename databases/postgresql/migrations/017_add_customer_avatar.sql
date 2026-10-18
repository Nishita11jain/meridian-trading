-- Sprint 10: a customer picks a predefined avatar, stored with the rest of their preferences.
--
-- avatar_key is one of eight fixed keys. The images themselves ship with the frontend; the database holds
-- only the key, so an avatar costs a few bytes per customer rather than an image per row.
--
-- The default applies to existing rows, which all get avatar_01. Safely re-runnable: the column is added
-- only while it is missing, and the constraint only while it is missing.

ALTER TABLE customer_preferences
    ADD COLUMN IF NOT EXISTS avatar_key VARCHAR(20) NOT NULL DEFAULT 'avatar_01';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_customer_preferences_avatar'
    ) THEN
        ALTER TABLE customer_preferences
            ADD CONSTRAINT ck_customer_preferences_avatar
            CHECK (avatar_key IN ('avatar_01', 'avatar_02', 'avatar_03', 'avatar_04',
                                  'avatar_05', 'avatar_06', 'avatar_07', 'avatar_08'));
    END IF;
END
$$;
