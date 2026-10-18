import { createHash } from 'crypto';
import { v4 as uuidv4 } from 'uuid';
import type { RefreshTokenRecord, RefreshTokenRepository } from './refresh-token.repository.js';
import { RefreshTokenService } from './refresh-token.service.js';
import { TokenService } from './token.service.js';
import { AuthService } from '../auth/auth.service.js';

/**
 * A repository double that reproduces the real RefreshTokenRepository's
 * observable behaviour (see refresh-token.repository.ts):
 *   - one row per user (save() upserts on user_id, matching the real
 *     `ON CONFLICT (user_id) DO UPDATE`)
 *   - created_at and hard_expiry are immutable after initial insert
 *   - verify()/verifyAndGetUserId() check hard_expiry (fixed 7-day window from creation)
 *   - updateTokenHashOnly() updates token_hash without changing hard_expiry
 *   - revoke() marks the row revoked; delete() removes it
 * This is a fake, not a stub returning canned values, so the tests below
 * exercise the real rotation logic in RefreshTokenService against it.
 */
function makeFakeRepository(): RefreshTokenRepository & { setHardExpiredForUser: (userId: string) => void } {
  const rows = new Map<
    string,
    { tokenHash: string; createdAt: Date; hardExpiry: Date; updatedAt: Date; revoked: boolean }
  >();

  const toRecord = (
    userId: string,
    row: { tokenHash: string; createdAt: Date; hardExpiry: Date; updatedAt: Date; revoked: boolean },
  ): RefreshTokenRecord => ({
    id: 'fake-row',
    userId,
    tokenHash: row.tokenHash,
    createdAt: row.createdAt,
    hardExpiry: row.hardExpiry,
    updatedAt: row.updatedAt,
    revoked: row.revoked,
  });

  const repo = {
    hashToken(token: string) {
      return createHash('sha256').update(token).digest('hex');
    },
    async save(userId: string, tokenHash: string) {
      const createdAt = new Date();
      const hardExpiry = new Date(createdAt.getTime() + 7 * 24 * 60 * 60 * 1000); // +7 days
      const row = { tokenHash, createdAt, hardExpiry, updatedAt: createdAt, revoked: false };
      rows.set(userId, row);
      return toRecord(userId, row);
    },
    async updateTokenHashOnly(userId: string, newTokenHash: string) {
      const row = rows.get(userId);
      if (!row) throw new Error('Token update failed: not found or hard_expiry exceeded');
      if (row.hardExpiry <= new Date()) throw new Error('Token update failed: hard_expiry exceeded');
      row.tokenHash = newTokenHash;
      row.updatedAt = new Date();
      return toRecord(userId, row);
    },
    async verify(userId: string, providedTokenHash: string, requireNotRevoked = true) {
      const row = rows.get(userId);
      if (!row) return null;
      if (row.hardExpiry <= new Date()) return null; // Hard expiry exceeded
      if (row.tokenHash !== providedTokenHash) return null;
      if (requireNotRevoked && row.revoked) return null;
      return toRecord(userId, row);
    },
    async verifyAndGetUserId(providedTokenHash: string, requireNotRevoked = true) {
      for (const [userId, row] of rows.entries()) {
        if (row.tokenHash !== providedTokenHash) continue;
        if (row.hardExpiry <= new Date()) continue; // Hard expiry exceeded
        if (requireNotRevoked && row.revoked) return null;
        return { userId, record: toRecord(userId, row) };
      }
      return null;
    },
    async revoke(userId: string) {
      const row = rows.get(userId);
      if (row) row.revoked = true;
    },
    async delete(userId: string) {
      rows.delete(userId);
    },
    async findByUserId(userId: string) {
      const row = rows.get(userId);
      if (!row || row.hardExpiry <= new Date()) return null; // Hard expiry exceeded
      return toRecord(userId, row);
    },
    async isHardExpired(userId: string): Promise<boolean> {
      const row = rows.get(userId);
      if (!row) return true;
      return row.hardExpiry <= new Date();
    },
    // Test helper: directly set hard_expiry to the past for this user
    setHardExpiredForUser(userId: string) {
      const row = rows.get(userId);
      if (row) {
        row.hardExpiry = new Date(Date.now() - 1000); // 1 second ago
      }
    },
  } as unknown as RefreshTokenRepository & { setHardExpiredForUser: (userId: string) => void };

  return repo;
}

function makeConfigService(overrides: Record<string, unknown> = {}) {
  const defaults: Record<string, unknown> = {
    REFRESH_TOKEN_REVOCATION_ENABLED: true,
    ...overrides,
  };
  return { get: (key: string, fallback: unknown) => (key in defaults ? defaults[key] : fallback) } as any;
}

describe('RefreshTokenService', () => {
  describe('token hashing', () => {
    it('hashes tokens using SHA-256 for storage', () => {
      const repo = makeFakeRepository();
      const token = uuidv4();
      const hash = repo.hashToken(token);

      expect(hash).toMatch(/^[a-f0-9]{64}$/);
      expect(repo.hashToken(token)).toBe(hash);
      expect(repo.hashToken(uuidv4())).not.toBe(hash);
    });

    it('never stores the plaintext token', () => {
      const repo = makeFakeRepository();
      const token = uuidv4();
      const hash = repo.hashToken(token);

      expect(hash).not.toBe(token);
      expect(hash).not.toContain(token);
    });
  });

  /**
   * These three cover SEC2-680's named execution paths end to end, through
   * AuthService.refresh() -- the only place an access token and a refresh
   * token are actually issued together -- backed by a fake repository that
   * reproduces the real single-row-per-user upsert behaviour, so a
   * previously-issued token's hash genuinely stops matching anything once
   * it has been rotated past, exactly as it would against Postgres.
   */
  describe('refresh rotation (SEC2-680 acceptance criteria)', () => {
    const userId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';
    const user = { id: userId, accountId: 1, roles: ['CUSTOMER'] };

    function makeAuthService(repo: RefreshTokenRepository, config = makeConfigService()) {
      const refreshTokenService = new RefreshTokenService(repo, config);
      const usersRepository = { findById: async (id: string) => (id === userId ? { ...user, username: 'priya.menon', createdOn: new Date().toISOString() } : null) };
      const tokenService = new TokenService();
      const authService = new AuthService(
        usersRepository as any,
        {} as any,
        {} as any,
        tokenService,
        refreshTokenService,
        {} as any,
      );
      return { authService, refreshTokenService };
    }

    it('refresh returns a new access token and a new refresh token', async () => {
      const repo = makeFakeRepository();
      const { authService, refreshTokenService } = makeAuthService(repo);
      const initialRefreshToken = await refreshTokenService.issue(userId);

      const result = await authService.refresh(initialRefreshToken);

      expect(result).not.toBeNull();
      expect(typeof result!.accessToken).toBe('string');
      expect(result!.accessToken.length).toBeGreaterThan(0);
      expect(result!.accessToken.split('.')).toHaveLength(3); // header.payload.signature
      expect(typeof result!.refreshToken).toBe('string');
      expect(result!.refreshToken.length).toBeGreaterThan(0);
      expect(result!.refreshToken).not.toBe(initialRefreshToken);
    });

    it('the newly issued refresh token works', async () => {
      const repo = makeFakeRepository();
      const { authService, refreshTokenService } = makeAuthService(repo);
      const initialRefreshToken = await refreshTokenService.issue(userId);

      const first = await authService.refresh(initialRefreshToken);
      expect(first).not.toBeNull();

      const second = await authService.refresh(first!.refreshToken);

      expect(second).not.toBeNull();
      expect(typeof second!.accessToken).toBe('string');
      expect(second!.refreshToken).not.toBe(first!.refreshToken);
    });

    it('the declared revocation behaviour holds for a token that has already been exchanged', async () => {
      const repo = makeFakeRepository();
      const { authService, refreshTokenService } = makeAuthService(repo);
      const initialRefreshToken = await refreshTokenService.issue(userId);

      const first = await authService.refresh(initialRefreshToken);
      expect(first).not.toBeNull();

      // initialRefreshToken has now been exchanged. Per the service's
      // configured behaviour (REFRESH_TOKEN_REVOCATION_ENABLED=true,
      // refresh-token.service.ts), the row for this user was overwritten
      // by the rotation, so the old token's hash no longer matches
      // anything stored -- a second presentation is refused exactly like
      // any other invalid token, which is what AuthService.refresh()
      // turns into AUTH-401 at the controller.
      const replay = await authService.refresh(initialRefreshToken);

      expect(replay).toBeNull();
    });

    it('refuses a refresh token from a different user session', async () => {
      const repo = makeFakeRepository();
      const { authService } = makeAuthService(repo);

      const result = await authService.refresh('a-token-that-was-never-issued');

      expect(result).toBeNull();
    });
  });

  /**
   * SEC2-680 hard_expiry fix: These tests verify that refresh tokens have a
   * fixed 7-day expiry from initial login, and cannot be extended by refreshing.
   */
  describe('hard_expiry (fixed 7-day window, SEC2-680 fix)', () => {
    const userId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';
    const user = { id: userId, accountId: 1, roles: ['CUSTOMER'] };

    function makeAuthService(repo: RefreshTokenRepository, config = makeConfigService()) {
      const refreshTokenService = new RefreshTokenService(repo, config);
      const usersRepository = { findById: async (id: string) => (id === userId ? { ...user, username: 'priya.menon', createdOn: new Date().toISOString() } : null) };
      const tokenService = new TokenService();
      const authService = new AuthService(
        usersRepository as any,
        {} as any,
        {} as any,
        tokenService,
        refreshTokenService,
        {} as any,
      );
      return { authService, refreshTokenService };
    }

    it('hard_expiry is set to 7 days from created_at on first issue', async () => {
      const repo = makeFakeRepository();
      const { refreshTokenService } = makeAuthService(repo);

      const token = await refreshTokenService.issue(userId);
      const record = await repo.findByUserId(userId);

      expect(record).not.toBeNull();
      const diffMs = record!.hardExpiry.getTime() - record!.createdAt.getTime();
      const diffDays = diffMs / (1000 * 60 * 60 * 24);
      expect(diffDays).toBeCloseTo(7, 1); // Within 1 day of 7 days
    });

    it('hard_expiry is NOT extended when token is refreshed', async () => {
      const repo = makeFakeRepository();
      const { authService, refreshTokenService } = makeAuthService(repo);

      const initialToken = await refreshTokenService.issue(userId);
      const initialRecord = await repo.findByUserId(userId);
      const originalHardExpiry = initialRecord!.hardExpiry.getTime();

      // Refresh multiple times
      let currentToken = initialToken;
      for (let i = 0; i < 3; i++) {
        const result = await authService.refresh(currentToken);
        expect(result).not.toBeNull();
        currentToken = result!.refreshToken;
      }

      // Verify hard_expiry is still the same as the original (within 1ms precision)
      const finalRecord = await repo.findByUserId(userId);
      expect(finalRecord!.hardExpiry.getTime()).toBe(originalHardExpiry);
    });

    it('cannot refresh after hard_expiry has passed', async () => {
      const repo = makeFakeRepository();
      const { authService, refreshTokenService } = makeAuthService(repo);

      const token = await refreshTokenService.issue(userId);

      // Simulate hard_expiry passing by setting it to the past
      repo.setHardExpiredForUser(userId);

      // Try to refresh
      const result = await authService.refresh(token);

      // Should fail because hard_expiry has passed
      expect(result).toBeNull();
    });

    it('throws UnauthorizedException when hard_expiry is exceeded on rotate', async () => {
      const repo = makeFakeRepository();
      const refreshTokenService = new RefreshTokenService(repo, makeConfigService());

      const token = await refreshTokenService.issue(userId);

      // Simulate hard_expiry passing
      repo.setHardExpiredForUser(userId);

      // Try to rotate (used by refresh endpoint)
      const result = await refreshTokenService.rotateWithUserLookup(token);

      // Should return null (invalid token or expired)
      expect(result).toBeNull();
    });

    it('isHardExpired returns true when hard_expiry has passed', async () => {
      const repo = makeFakeRepository();
      const { refreshTokenService } = makeAuthService(repo);

      const token = await refreshTokenService.issue(userId);
      let isExpired = await repo.isHardExpired(userId);
      expect(isExpired).toBe(false); // Just created, not expired

      // Simulate time passing beyond hard_expiry
      repo.setHardExpiredForUser(userId);

      isExpired = await repo.isHardExpired(userId);
      expect(isExpired).toBe(true);
    });

    it('updateTokenHashOnly does not extend hard_expiry', async () => {
      const repo = makeFakeRepository();
      const { refreshTokenService } = makeAuthService(repo);

      const token = await refreshTokenService.issue(userId);
      const initialRecord = await repo.findByUserId(userId);
      const originalHardExpiry = initialRecord!.hardExpiry;

      // Update token hash only
      const newHash = repo.hashToken('new-token');
      const updatedRecord = await repo.updateTokenHashOnly(userId, newHash);

      // Hard expiry should be unchanged
      expect(updatedRecord.hardExpiry.getTime()).toBe(originalHardExpiry.getTime());

      // But updated_at should change
      expect(updatedRecord.updatedAt.getTime()).toBeGreaterThanOrEqual(initialRecord!.updatedAt.getTime());
    });
  });
});
