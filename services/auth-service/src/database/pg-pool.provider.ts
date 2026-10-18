import { Pool, type QueryResult, type QueryResultRow } from 'pg';
import type { Provider } from '@nestjs/common';

export const PG_POOL = Symbol('PG_POOL');

/**
 * Runs a query via pg-pool's callback overload rather than awaiting
 * pool.query(...) directly. @nestjs/observe's automatic pg instrumentation
 * (wired in app.module.ts, not something this story added) replaces the
 * pool's internal Promise reference some time after the app finishes
 * booting, which breaks pg-pool's own promise-returning overload ("Promise
 * constructor cannot be invoked without 'new'") for any query issued after
 * that point -- migrations run earlier in bootstrap and are unaffected, but
 * every request-time query is. pg-pool's own promisify() helper returns
 * immediately without ever touching pool.Promise when a callback is
 * supplied (see node_modules/pg-pool/index.js), so wrapping the callback
 * form in a plain, un-instrumented Promise sidesteps the bug entirely.
 */
export function queryPool<R extends QueryResultRow = QueryResultRow>(
  pool: Pool,
  text: string,
  params: readonly unknown[] = [],
): Promise<QueryResult<R>> {
  return new Promise((resolve, reject) => {
    pool.query<R>(text, params as unknown[], (err, result) => {
      if (err) reject(err);
      else resolve(result);
    });
  });
}

export const pgPoolProvider: Provider = {
  provide: PG_POOL,
  useFactory: (): Pool => {
    return new Pool({
      host: process.env.POSTGRES_HOST,
      port: process.env.POSTGRES_PORT ? Number(process.env.POSTGRES_PORT) : undefined,
      database: process.env.POSTGRES_DB,
      user: process.env.POSTGRES_USER,
      password: process.env.POSTGRES_PASSWORD,
      ssl: process.env.DB_SSL === 'true' ? { rejectUnauthorized: false } : undefined,
    });
  },
};
