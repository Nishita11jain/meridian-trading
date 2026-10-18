-- SEC2-680: Refresh token issuance and rotation
--
-- Stores hashed refresh tokens (never plaintext) to prevent session takeover
-- if database is compromised. Tokens are rotated on every refresh: the
-- presented token is deleted (or marked revoked if revocation is enabled).
--
-- Hash algorithm: SHA-256 (via pgcrypto). Refresh tokens are short-lived
-- (7 days) and rotated frequently, so password-strength hashing (argon2/bcrypt)
-- is unnecessary overhead; SHA-256 is sufficient.
--
-- Revocation decision per SEC2-680: set REVOCATION_ENABLED in config.
-- If true: revoked tokens are marked and rejected on verification.
-- If false: tokens simply expire; old tokens are deleted on rotation.

CREATE TABLE IF NOT EXISTS auth.refresh_tokens (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID          NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    token_hash        TEXT          NOT NULL,
    expires_at        TIMESTAMPTZ   NOT NULL,
    revoked           BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Prevent multiple active refresh tokens per user (one-token-at-a-time rotation)
    CONSTRAINT uq_auth_refresh_tokens_user_id UNIQUE (user_id),
    -- Index for revocation check during refresh
    CONSTRAINT ck_auth_refresh_tokens_not_past_expiry CHECK (expires_at > now())
);

-- Partial index: find non-revoked tokens for a user. Expiry is deliberately not
-- in the predicate: Postgres requires index predicates to be IMMUTABLE and
-- now() is not, so expiry is filtered at query time instead.
CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_active
    ON auth.refresh_tokens(user_id)
    WHERE revoked = FALSE;
