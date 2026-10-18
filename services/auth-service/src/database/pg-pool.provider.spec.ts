import { jest } from '@jest/globals';
import type { Pool } from 'pg';
import { queryPool } from './pg-pool.provider.js';

function fakePool(invoke: (cb: (err: unknown, result?: unknown) => void) => void): Pool {
  return {
    query: jest.fn((_text: string, _params: unknown[], cb: (err: unknown, result?: unknown) => void) => {
      invoke(cb);
    }),
  } as unknown as Pool;
}

describe('queryPool', () => {
  it('resolves with the result when the callback succeeds', async () => {
    const rows = [{ id: 1 }];
    const pool = fakePool((cb) => cb(null, { rows }));

    await expect(queryPool(pool, 'SELECT 1', [])).resolves.toEqual({ rows });
  });

  it('rejects when the callback reports an error', async () => {
    const dbError = new Error('connection lost');
    const pool = fakePool((cb) => cb(dbError));

    await expect(queryPool(pool, 'SELECT 1', [])).rejects.toBe(dbError);
  });

  it('passes text and params straight through to pool.query', async () => {
    const pool = fakePool((cb) => cb(null, { rows: [] }));
    await queryPool(pool, 'SELECT * FROM auth.users WHERE id = $1', ['abc']);

    expect(pool.query).toHaveBeenCalledWith(
      'SELECT * FROM auth.users WHERE id = $1',
      ['abc'],
      expect.any(Function),
    );
  });

  it('defaults params to an empty array when omitted', async () => {
    const pool = fakePool((cb) => cb(null, { rows: [] }));
    await queryPool(pool, 'SELECT 1');

    expect(pool.query).toHaveBeenCalledWith('SELECT 1', [], expect.any(Function));
  });
});
