-- Logins for the seeded customers. Applied after seed-data.sql, and safe to run again.
--
-- One login per trading account (auth.users.account_id is unique). Every login uses the same demo password,
-- Meridian#2026, stored only as the argon2id hash the auth service itself would write. The auth schema is created
-- here as well, in step with services/auth-service/src/database/migrations/001_init_auth_schema.sql, because the
-- auth service creates it on its first start and this file can run before that.

CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE IF NOT EXISTS auth.users (
    id            UUID          PRIMARY KEY,
    username      VARCHAR(64)   NOT NULL,
    password_hash TEXT          NOT NULL,
    account_id    BIGINT        NOT NULL,
    roles         TEXT[]        NOT NULL DEFAULT ARRAY['CUSTOMER'],
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_auth_users_username UNIQUE (username),
    CONSTRAINT uq_auth_users_account_id UNIQUE (account_id),
    CONSTRAINT ck_auth_users_roles CHECK (roles <@ ARRAY['CUSTOMER', 'ADMIN']::text[])
);

INSERT INTO auth.users (id, username, password_hash, account_id, roles)
SELECT v.id::uuid, v.username, v.password_hash, a.id, ARRAY['CUSTOMER']
FROM (VALUES
    ('7882a1de-2951-59b9-aacd-4eac887e1b12', 'arun.us', '$argon2id$v=19$m=65536,p=1,t=2$d6RogK5UYmfKbzzHM94fTw$MI4pyc2r8488lgAzVib0eQi6VM5CGbuTixuh0zzzekY', 'ETP-4101'),  -- Arun U S (ACTIVE)
    ('b28f7166-ffb2-52e9-b64c-5b72fdc8747b', 'nishita.jain', '$argon2id$v=19$m=65536,p=1,t=2$I/EwwgdKwebHzVGYaQFwOw$VAjTSA31mmh6xwx29tljk3KDjF8kE2VllPbOYK9jHng', 'ETP-4102'),  -- Nishita Jain (ACTIVE)
    ('2943a817-8e1c-5cbb-a750-146dbdd0df50', 'parthasarathi.bhattacharya', '$argon2id$v=19$m=65536,p=1,t=2$7frOVwNr4q3nKOJGTIoOZg$zkLNV4npQTirW33r3bYs+YW2JkeF5x2+MwpbZd1ijlY', 'ETP-4103'),  -- Parthasarathi Bhattacharya (ACTIVE)
    ('fb6bc3f5-f1ad-5dfe-8bdc-50c5102fb8a3', 'ananya.p', '$argon2id$v=19$m=65536,p=1,t=2$j1wc4u+laHASFTiXezc7qg$6YPHMMDi1HLjgrD1Ah214eUyvtb7FyyjpjymtbMkVPY', 'ETP-4104'),  -- Ananya P (ACTIVE)
    ('01341998-2a71-5df8-be11-e6016e58969f', 'neel.varma', '$argon2id$v=19$m=65536,p=1,t=2$PsdWElX5oxqL9X1NQys2qw$DoQuqYC9pH8LTH3lt8oH5AbDui6V7YGVFKbwWJdtEcw', 'ETP-4105')  -- Neel Varma (SUSPENDED)
) AS v(id, username, password_hash, account_ref)
JOIN accounts a ON a.account_id = v.account_ref
ON CONFLICT DO NOTHING;
