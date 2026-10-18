-- SEC2-677: the auth service's credential store.
--
-- Lives in its own schema, deliberately not the trading schema's `public`.
-- The block diagram at leap-architecture.pages.dev/system-block.html is
-- explicit that "no trading table is touched from here" -- account_id below
-- is a plain BIGINT with no foreign key against accounts.id, so this schema
-- never references the trading schema at all, not even at the constraint
-- level. Existence/active-status of the referenced account is validated at
-- the application layer (see account-validation.service.ts), not by
-- Postgres.
CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE IF NOT EXISTS auth.users (
    id            UUID          PRIMARY KEY,
    username      VARCHAR(64)   NOT NULL,
    password_hash TEXT          NOT NULL,
    account_id    BIGINT        NOT NULL,
    roles         TEXT[]        NOT NULL DEFAULT ARRAY['CUSTOMER'],
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_auth_users_username UNIQUE (username),
    -- One login per trading account. Every seeded account maps to one named
    -- holder (see database/seed/accounts.csv); letting two unrelated logins
    -- both claim account 1 looks like an accident, not a feature, so the
    -- first registration to claim an accountId keeps it -- AUTH-409s a
    -- second attempt the same way a taken username does.
    CONSTRAINT uq_auth_users_account_id UNIQUE (account_id),
    CONSTRAINT ck_auth_users_roles CHECK (roles <@ ARRAY['CUSTOMER', 'ADMIN']::text[])
);
