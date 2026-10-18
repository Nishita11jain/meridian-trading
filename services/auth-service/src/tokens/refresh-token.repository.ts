import { Inject, Injectable } from '@nestjs/common';
import type { Pool, QueryResult } from 'pg';
import { PG_POOL, queryPool } from '../database/pg-pool.provider.js';
import { createHash } from 'crypto';
import { timingSafeEqual } from 'crypto';

export type RefreshTokenRecord = {
  id: string;
  userId: string;
  tokenHash: string;
  createdAt: Date;
  hardExpiry: Date;
  updatedAt: Date;
  revoked: boolean;
};

type RefreshTokenRow = {
  id: string;
  user_id: string;
  token_hash: string;
  created_at: Date;
  hard_expiry: Date;
  updated_at: Date;
  revoked: boolean;
};

@Injectable()
export class RefreshTokenRepository {
  constructor(@Inject(PG_POOL) private readonly pool: Pool) {}

  /**
   * Hash a refresh token using SHA-256.
   * Refresh tokens are short-lived and rotated frequently, so a fast hash
   * (not argon2/bcrypt) is appropriate.
   */
  hashToken(token: string): string {
    return createHash('sha256').update(token).digest('hex');
  }

  /**
   * Save the refresh token a LOGIN issues (hashed).
   * A login starts a new session, so it always starts a new 7-day window: created_at and
   * hard_expiry are (re)written, for a first login and for a later one alike. Leaving them alone
   * on conflict would anchor the window to the user's very first login, and from day 8 no refresh
   * token issued by any later login would ever work.
   * A REFRESH does not come through here: it uses updateTokenHashOnly, which keeps the window.
   * Enforces one-token-at-a-time: overwrites existing token for user (if any).
   */
  async save(userId: string, tokenHash: string): Promise<RefreshTokenRecord> {
    const createdAt = new Date();
    const hardExpiry = new Date(createdAt.getTime() + 7 * 24 * 60 * 60 * 1000); // +7 days

    const result = await queryPool<RefreshTokenRow>(
      this.pool,
      `INSERT INTO auth.refresh_tokens (user_id, token_hash, created_at, hard_expiry, revoked)
       VALUES ($1, $2, $3, $4, FALSE)
       ON CONFLICT (user_id) DO UPDATE
         SET token_hash = $2, revoked = FALSE, created_at = $3, hard_expiry = $4
       RETURNING id, user_id, token_hash, created_at, hard_expiry, updated_at, revoked`,
      [userId, tokenHash, createdAt, hardExpiry],
    );
    return this.toRecord(result.rows[0]);
  }

  /**
   * Update ONLY the token_hash for a user, keeping created_at and hard_expiry immutable.
   * Used on refresh to rotate the token without extending the 7-day window.
   * Throws if token's hard_expiry has already passed.
   */
  async updateTokenHashOnly(userId: string, newTokenHash: string): Promise<RefreshTokenRecord> {
    const result = await queryPool<RefreshTokenRow>(
      this.pool,
      `UPDATE auth.refresh_tokens 
       SET token_hash = $1, revoked = FALSE
       WHERE user_id = $2 AND hard_expiry > now()
       RETURNING id, user_id, token_hash, created_at, hard_expiry, updated_at, revoked`,
      [newTokenHash, userId],
    );

    if (result.rows.length === 0) {
      throw new Error('Token update failed: not found or hard_expiry exceeded');
    }

    return this.toRecord(result.rows[0]);
  }

  /**
   * Verify a refresh token exists, matches the stored hash, has not exceeded hard_expiry,
   * and (if revocation is enabled) is not revoked.
   *
   * Returns the stored record if valid, null otherwise.
   * Uses constant-time comparison to avoid timing attacks.
   */
  async verify(userId: string, providedTokenHash: string, requireNotRevoked = true): Promise<RefreshTokenRecord | null> {
    const result = await queryPool<RefreshTokenRow>(
      this.pool,
      `SELECT id, user_id, token_hash, created_at, hard_expiry, updated_at, revoked
       FROM auth.refresh_tokens
       WHERE user_id = $1 AND hard_expiry > now()`,
      [userId],
    );

    if (result.rows.length === 0) {
      return null; // Token not found or hard_expiry exceeded
    }

    const stored = result.rows[0];

    // Constant-time comparison to prevent timing attacks
    try {
      timingSafeEqual(Buffer.from(providedTokenHash, 'hex'), Buffer.from(stored.token_hash, 'hex'));
    } catch {
      return null; // Hash mismatch
    }

    // Check revocation status if required
    if (requireNotRevoked && stored.revoked) {
      return null; // Token has been revoked
    }

    return this.toRecord(stored);
  }

  /**
   * Verify a refresh token and return the associated userId if valid.
   * This is used when the endpoint receives just the token (no userId context).
   * Checks hard_expiry to ensure the 7-day window from initial login has not passed.
   * Returns { userId, record } if valid, null otherwise.
   */
  async verifyAndGetUserId(
    providedTokenHash: string,
    requireNotRevoked = true,
  ): Promise<{ userId: string; record: RefreshTokenRecord } | null> {
    // Search for any token with this hash that has not exceeded hard_expiry
    const result = await queryPool<RefreshTokenRow>(
      this.pool,
      `SELECT id, user_id, token_hash, created_at, hard_expiry, updated_at, revoked
       FROM auth.refresh_tokens
       WHERE token_hash = $1 AND hard_expiry > now()`,
      [providedTokenHash],
    );

    if (result.rows.length === 0) {
      return null; // Token not found or hard_expiry exceeded
    }

    const stored = result.rows[0];

    // Constant-time comparison to prevent timing attacks
    try {
      timingSafeEqual(Buffer.from(providedTokenHash, 'hex'), Buffer.from(stored.token_hash, 'hex'));
    } catch {
      return null; // Hash mismatch (shouldn't happen, but be safe)
    }

    // Check revocation status if required
    if (requireNotRevoked && stored.revoked) {
      return null; // Token has been revoked
    }

    return {
      userId: stored.user_id,
      record: this.toRecord(stored),
    };
  }

  /**
   * Revoke a refresh token (mark as revoked in DB).
   * Called after a refresh to prevent reuse of the old token.
   * Requires revocation to be enabled (per config).
   */
  async revoke(userId: string): Promise<void> {
    await queryPool<RefreshTokenRow>(
      this.pool,
      `UPDATE auth.refresh_tokens
       SET revoked = TRUE
       WHERE user_id = $1`,
      [userId],
    );
  }

  /**
   * Delete a refresh token entirely.
   * Simpler alternative to revocation: just remove the record.
   */
  async delete(userId: string): Promise<void> {
    await queryPool(
      this.pool,
      `DELETE FROM auth.refresh_tokens WHERE user_id = $1`,
      [userId],
    );
  }

  /**
   * Find a refresh token record by user ID if hard_expiry has not passed.
   */
  async findByUserId(userId: string): Promise<RefreshTokenRecord | null> {
    const result = await queryPool<RefreshTokenRow>(
      this.pool,
      `SELECT id, user_id, token_hash, created_at, hard_expiry, updated_at, revoked
       FROM auth.refresh_tokens
       WHERE user_id = $1 AND hard_expiry > now()`,
      [userId],
    );
    return result.rows[0] ? this.toRecord(result.rows[0]) : null;
  }

  /**
   * Check if a user's refresh token has exceeded its hard_expiry.
   * Returns true if hard_expiry has passed or token does not exist.
   * Returns false if token is still within 7-day window.
   */
  async isHardExpired(userId: string): Promise<boolean> {
    const result = await queryPool<{ hard_expiry: Date }>(
      this.pool,
      `SELECT hard_expiry FROM auth.refresh_tokens WHERE user_id = $1`,
      [userId],
    );

    if (result.rows.length === 0) {
      return true; // No token exists
    }

    return new Date() > result.rows[0].hard_expiry; // Has expiry passed?
  }

  private toRecord(row: RefreshTokenRow): RefreshTokenRecord {
    return {
      id: row.id,
      userId: row.user_id,
      tokenHash: row.token_hash,
      createdAt: row.created_at,
      hardExpiry: row.hard_expiry,
      updatedAt: row.updated_at,
      revoked: row.revoked,
    };
  }
}
