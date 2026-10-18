import { jest } from '@jest/globals';
import type { Pool } from 'pg';
import { RefreshTokenRepository } from './refresh-token.repository.js';

function fakePool(
  handler: (text: string, params: unknown[]) => { rows: unknown[] },
): Pool {
  return {
    query: jest.fn((text: string, params: unknown[], cb: (err: unknown, result?: unknown) => void) => {
      cb(null, handler(text, params));
    }),
  } as unknown as Pool;
}

const userId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

function row(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'row-id',
    user_id: userId,
    token_hash: 'a'.repeat(64),
    created_at: new Date('2026-01-01T00:00:00Z'),
    hard_expiry: new Date('2026-01-08T00:00:00Z'),
    updated_at: new Date('2026-01-01T00:00:00Z'),
    revoked: false,
    ...overrides,
  };
}

describe('RefreshTokenRepository', () => {
  describe('hashToken', () => {
    it('is a deterministic SHA-256 hex digest', () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new RefreshTokenRepository(pool);

      const hash = repo.hashToken('some-refresh-token');

      expect(hash).toMatch(/^[a-f0-9]{64}$/);
      expect(repo.hashToken('some-refresh-token')).toBe(hash);
    });

    it('produces different hashes for different tokens', () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new RefreshTokenRepository(pool);

      expect(repo.hashToken('token-a')).not.toBe(repo.hashToken('token-b'));
    });
  });

  describe('save', () => {
    it('inserts with a hard_expiry seven days after created_at', async () => {
      const pool = fakePool(() => ({ rows: [row()] }));
      const repo = new RefreshTokenRepository(pool);

      await repo.save(userId, 'a'.repeat(64));

      const [, params] = (pool.query as jest.Mock).mock.calls[0];
      const [, , createdAt, hardExpiry] = params;
      expect(hardExpiry.getTime() - createdAt.getTime()).toBe(7 * 24 * 60 * 60 * 1000);
    });

    it('a later login restarts the seven-day window instead of inheriting the first one', async () => {
      const pool = fakePool(() => ({ rows: [row()] }));
      const repo = new RefreshTokenRepository(pool);

      await repo.save(userId, 'a'.repeat(64));

      const [sql] = (pool.query as jest.Mock).mock.calls[0];
      const onConflict = String(sql).split('ON CONFLICT')[1] ?? '';
      expect(onConflict).toMatch(/created_at\s*=\s*\$3/);
      expect(onConflict).toMatch(/hard_expiry\s*=\s*\$4/);
      expect(onConflict).toMatch(/revoked\s*=\s*FALSE/);
    });

    it('a refresh does not go through save, so it cannot move the window', async () => {
      const pool = fakePool(() => ({ rows: [row()] }));
      const repo = new RefreshTokenRepository(pool);

      await repo.updateTokenHashOnly(userId, 'b'.repeat(64));

      const [sql] = (pool.query as jest.Mock).mock.calls[0];
      expect(String(sql)).not.toMatch(/created_at\s*=/);
      expect(String(sql)).not.toMatch(/hard_expiry\s*=/);
    });

    it('returns the mapped record', async () => {
      const pool = fakePool(() => ({ rows: [row()] }));
      const repo = new RefreshTokenRepository(pool);

      const record = await repo.save(userId, 'a'.repeat(64));

      expect(record.userId).toBe(userId);
      expect(record.revoked).toBe(false);
    });
  });

  describe('updateTokenHashOnly', () => {
    it('returns the updated record on success', async () => {
      const pool = fakePool(() => ({ rows: [row({ token_hash: 'b'.repeat(64) })] }));
      const repo = new RefreshTokenRepository(pool);

      const record = await repo.updateTokenHashOnly(userId, 'b'.repeat(64));

      expect(record.tokenHash).toBe('b'.repeat(64));
    });

    it('throws when no row matches (not found or hard_expiry exceeded)', async () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.updateTokenHashOnly(userId, 'b'.repeat(64))).rejects.toThrow(
        /not found or hard_expiry exceeded/,
      );
    });
  });

  describe('verify', () => {
    it('returns the record when the hash matches and revocation is not required', async () => {
      const hash = 'a'.repeat(64);
      const pool = fakePool(() => ({ rows: [row({ token_hash: hash, revoked: true })] }));
      const repo = new RefreshTokenRepository(pool);

      const record = await repo.verify(userId, hash, false);

      expect(record).not.toBeNull();
    });

    it('returns null when revoked and revocation is required', async () => {
      const hash = 'a'.repeat(64);
      const pool = fakePool(() => ({ rows: [row({ token_hash: hash, revoked: true })] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.verify(userId, hash, true)).resolves.toBeNull();
    });

    it('returns null when no row is found (unknown user or hard_expiry passed)', async () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.verify(userId, 'a'.repeat(64))).resolves.toBeNull();
    });

    it('returns null when the provided hash does not match the stored one', async () => {
      // Different length hex strings make timingSafeEqual throw, which the
      // repository treats the same as "no match".
      const pool = fakePool(() => ({ rows: [row({ token_hash: 'a'.repeat(64) })] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.verify(userId, 'b'.repeat(32))).resolves.toBeNull();
    });
  });

  describe('verifyAndGetUserId', () => {
    it('returns the userId and record when the hash matches', async () => {
      const hash = 'a'.repeat(64);
      const pool = fakePool(() => ({ rows: [row({ token_hash: hash })] }));
      const repo = new RefreshTokenRepository(pool);

      const result = await repo.verifyAndGetUserId(hash);

      expect(result?.userId).toBe(userId);
    });

    it('returns null when revoked and revocation is required', async () => {
      const hash = 'a'.repeat(64);
      const pool = fakePool(() => ({ rows: [row({ token_hash: hash, revoked: true })] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.verifyAndGetUserId(hash, true)).resolves.toBeNull();
    });

    it('returns null when no row is found', async () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.verifyAndGetUserId('a'.repeat(64))).resolves.toBeNull();
    });
  });

  describe('revoke / delete', () => {
    it('revoke issues an UPDATE setting revoked = TRUE for the user', async () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new RefreshTokenRepository(pool);

      await repo.revoke(userId);

      expect(pool.query).toHaveBeenCalledWith(
        expect.stringContaining('SET revoked = TRUE'),
        [userId],
        expect.any(Function),
      );
    });

    it('delete issues a DELETE for the user', async () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new RefreshTokenRepository(pool);

      await repo.delete(userId);

      expect(pool.query).toHaveBeenCalledWith(
        expect.stringContaining('DELETE FROM auth.refresh_tokens'),
        [userId],
        expect.any(Function),
      );
    });
  });

  describe('findByUserId', () => {
    it('returns the mapped record when found', async () => {
      const pool = fakePool(() => ({ rows: [row()] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.findByUserId(userId)).resolves.not.toBeNull();
    });

    it('returns null when not found', async () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.findByUserId(userId)).resolves.toBeNull();
    });
  });

  describe('isHardExpired', () => {
    it('returns true when no token row exists', async () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.isHardExpired(userId)).resolves.toBe(true);
    });

    it('returns true when hard_expiry is in the past', async () => {
      const pool = fakePool(() => ({ rows: [{ hard_expiry: new Date('2000-01-01T00:00:00Z') }] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.isHardExpired(userId)).resolves.toBe(true);
    });

    it('returns false when hard_expiry is in the future', async () => {
      const future = new Date(Date.now() + 24 * 60 * 60 * 1000);
      const pool = fakePool(() => ({ rows: [{ hard_expiry: future }] }));
      const repo = new RefreshTokenRepository(pool);

      await expect(repo.isHardExpired(userId)).resolves.toBe(false);
    });
  });
});
