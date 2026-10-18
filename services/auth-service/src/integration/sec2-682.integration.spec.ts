import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import jwt from 'jsonwebtoken';
import { Pool } from 'pg';

/**
 * SEC2-682 integration test: a token issued by the real auth-service is
 * accepted by a protected Trade REST API route, and the same route refuses
 * both a request with no token and a token signed with a key the platform
 * does not trust.
 *
 * This is NOT part of `npm test`. It needs two live services -- auth-service
 * on :3000 and order-service on :8081 -- and is run with
 * `npm run test:integration`. It talks to them over plain fetch() and
 * deliberately imports nothing from src/, so it never pulls in
 * @nestjs/throttler (which cannot be loaded under Jest; see sec2-681.spec.ts).
 *
 * The database is read to pick a genuinely unclaimed accountId, and the test
 * user it registers is deleted afterwards (its refresh-token row goes with
 * it via ON DELETE CASCADE), so reruns neither collide nor use up accounts.
 */
const AUTH_URL = process.env.AUTH_BASE_URL ?? 'http://localhost:3000';
const TRADE_URL = process.env.TRADE_API_BASE_URL ?? 'http://localhost:8081';

// Same source auth-service reads its own configuration from: the root .env.
// Parsed by hand because process.loadEnvFile() writes to the real process
// environment, not the copy Jest gives each test file. Values already set in
// the environment win.
try {
  for (const line of readFileSync(resolve(process.cwd(), '../../.env'), 'utf8').split(/\r?\n/)) {
    const match = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
    if (match && process.env[match[1]] === undefined) {
      process.env[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
    }
  }
} catch {
  // Fall back to whatever is already in the environment.
}

describe('SEC2-682: token from auth-service against a protected Trade REST API route', () => {
  const pool = new Pool({
    host: process.env.POSTGRES_HOST,
    port: process.env.POSTGRES_PORT ? Number(process.env.POSTGRES_PORT) : undefined,
    database: process.env.POSTGRES_DB,
    user: process.env.POSTGRES_USER,
    password: process.env.POSTGRES_PASSWORD,
  });

  const username = `it.sec2682.${Date.now()}`;
  const password = 'correct horse battery staple';
  let accountId: number;
  let accessToken: string;

  const accountUrl = () => `${TRADE_URL}/api/v1/accounts/${accountId}`;

  beforeAll(async () => {
    const unclaimed = await pool.query<{ id: string }>(
      `SELECT a.id
         FROM public.accounts a
        WHERE a.status = 'ACTIVE'
          AND NOT EXISTS (SELECT 1 FROM auth.users u WHERE u.account_id = a.id)
        ORDER BY a.id
        LIMIT 1`,
    );
    if (unclaimed.rows.length === 0) {
      throw new Error('No unclaimed ACTIVE account left to register the test user against');
    }
    accountId = Number(unclaimed.rows[0].id);

    const register = await fetch(`${AUTH_URL}/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, accountId }),
    });
    if (register.status !== 201) {
      throw new Error(`register returned ${register.status}: ${await register.text()}`);
    }

    const login = await fetch(`${AUTH_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    if (login.status !== 200) {
      throw new Error(`login returned ${login.status}: ${await login.text()}`);
    }
    accessToken = ((await login.json()) as { accessToken: string }).accessToken;
  }, 30000);

  afterAll(async () => {
    await pool.query('DELETE FROM auth.users WHERE username = $1', [username]);
    await pool.end();
  });

  it('a token issued by auth-service is accepted by a protected Trade REST API route', async () => {
    const response = await fetch(accountUrl(), {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    expect(response.status).toBe(200);
  });

  it('a request with no token is refused', async () => {
    const response = await fetch(accountUrl());
    expect(response.status).toBe(401);
  });

  it('a token signed with a key the platform should not trust is refused', async () => {
    // Same claim shape and issuer as the genuine token -- taken from it --
    // signed with a different secret, so the signature is the only thing wrong.
    const genuine = jwt.decode(accessToken) as { sub: string; accountId: number; roles: string[]; iss: string };
    const forged = jwt.sign(
      { sub: genuine.sub, accountId: genuine.accountId, roles: genuine.roles, iss: genuine.iss },
      'a-completely-different-signing-key-32-bytes-min',
      { algorithm: 'HS256', expiresIn: '15m' },
    );

    const response = await fetch(accountUrl(), {
      headers: { Authorization: `Bearer ${forged}` },
    });
    expect(response.status).toBe(401);
  });
});
