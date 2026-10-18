import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import type { Pool } from 'pg';
import { queryPool } from './pg-pool.provider.js';

const MIGRATIONS_DIR = join(dirname(fileURLToPath(import.meta.url)), 'migrations');

const MIGRATIONS = ['001_init_auth_schema.sql', '002_create_refresh_tokens_table.sql', '003_update_refresh_tokens_fixed_expiry.sql'];

/**
 * Applies the auth schema's migrations in order. Idempotent (CREATE ... IF
 * NOT EXISTS throughout), safe to run on every boot. Gated by
 * AUTH_RUN_MIGRATIONS so a deployment that manages schema separately can
 * opt out.
 */
export async function runMigrations(pool: Pool): Promise<void> {
  if (process.env.AUTH_RUN_MIGRATIONS === 'false') {
    return;
  }
  for (const file of MIGRATIONS) {
    const sql = readFileSync(join(MIGRATIONS_DIR, file), 'utf8');
    await queryPool(pool, sql);
  }
}
