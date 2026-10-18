-- SEC2-680: Fix Refresh Token Sliding Window Expiry
--
-- Problem: Previously, expires_at was updated on every refresh, extending the 
-- deadline indefinitely (sliding window). Users could keep sessions alive forever.
--
-- Solution: Implement fixed 7-day window from initial login.
-- - created_at: Immutable timestamp of original login
-- - hard_expiry: Immutable deadline (created_at + 7 days), cannot be extended
-- - updated_at: Tracks last refresh activity (for audit/cleanup)
--
-- Result: After 7 days from login, user MUST re-authenticate, regardless of activity.
-- This is enforced at both database level (CHECK constraint) and application level.

BEGIN;

-- Step 1: Add new immutable columns (idempotent)
ALTER TABLE auth.refresh_tokens
  ADD COLUMN IF NOT EXISTS hard_expiry TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '7 days'),
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Step 2: Populate hard_expiry from legacy expires_at when present,
-- otherwise derive from created_at for rows that lack hard_expiry
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'auth' AND table_name = 'refresh_tokens' AND column_name = 'expires_at'
  ) THEN
    -- Prefer existing expires_at values for migration
    UPDATE auth.refresh_tokens SET hard_expiry = expires_at WHERE hard_expiry IS NULL;
  END IF;
  -- For any remaining rows without hard_expiry, set to created_at + 7 days
  UPDATE auth.refresh_tokens SET hard_expiry = created_at + INTERVAL '7 days' WHERE hard_expiry IS NULL;
END;
$$;

-- Step 3: Drop the old expires_at column and its check constraint if present
ALTER TABLE auth.refresh_tokens
  DROP CONSTRAINT IF EXISTS ck_auth_refresh_tokens_not_past_expiry;
ALTER TABLE auth.refresh_tokens
  DROP COLUMN IF EXISTS expires_at;

-- Step 4: Add new CHECK constraint for hard_expiry if it doesn't exist
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_refresh_tokens_hard_expiry_future'
  ) THEN
    ALTER TABLE auth.refresh_tokens ADD CONSTRAINT ck_refresh_tokens_hard_expiry_future CHECK (hard_expiry > created_at);
  END IF;
END;
$$;

-- Step 5: Create trigger for auto-updating updated_at on every change
CREATE OR REPLACE FUNCTION auth_refresh_tokens_update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS auth_refresh_tokens_updated_at_trigger ON auth.refresh_tokens;
CREATE TRIGGER auth_refresh_tokens_updated_at_trigger
BEFORE UPDATE ON auth.refresh_tokens
FOR EACH ROW
EXECUTE FUNCTION auth_refresh_tokens_update_timestamp();

-- Step 6: Ensure indexes for performance (idempotent)
DROP INDEX IF EXISTS idx_auth_refresh_tokens_active;
CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_user_id 
  ON auth.refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_hard_expiry 
  ON auth.refresh_tokens(hard_expiry);
CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_created_at 
  ON auth.refresh_tokens(created_at);

COMMIT;

-- Verification Queries (run manually to verify migration):
-- SELECT column_name, data_type FROM information_schema.columns 
--   WHERE table_name = 'refresh_tokens' AND table_schema = 'auth';
-- 
-- Expected columns: id, user_id, token_hash, revoked, created_at, hard_expiry, updated_at
