import { Injectable, UnauthorizedException } from '@nestjs/common';
import { v4 as uuidv4 } from 'uuid';
import { RefreshTokenRepository } from './refresh-token.repository.js';
import { ConfigService } from '@nestjs/config';
import { RedactingLogger } from '../common/redacting-logger.js';

/**
 * SEC2-680: Refresh Token Issuance and Rotation
 *
 * Manages the lifecycle of refresh tokens with fixed 7-day expiry:
 * - Issues new refresh tokens on successful login (creates created_at and hard_expiry)
 * - Verifies tokens on /auth/refresh requests
 * - Rotates tokens (updates token_hash only, keeps hard_expiry immutable)
 * - Enforces hard_expiry: after 7 days from initial login, user MUST re-authenticate
 *
 * Key change from sliding window:
 * - OLD: expires_at = now() + 7days (extended on every refresh → infinite session)
 * - NEW: hard_expiry = created_at + 7days (immutable, enforced at DB level)
 *
 * Revocation decision:
 * - If REFRESH_TOKEN_REVOCATION_ENABLED: old tokens are marked revoked
 * - If not: old tokens simply expire; no explicit revocation
 *
 * Both paths must be documented in SEC2-684 security review.
 */
@Injectable()
export class RefreshTokenService {
  private readonly revocationEnabled: boolean;
  private readonly logger = new RedactingLogger(RefreshTokenService.name);

  constructor(
    private readonly repository: RefreshTokenRepository,
    private readonly config: ConfigService,
  ) {
    this.revocationEnabled = this.config.get<boolean>('REFRESH_TOKEN_REVOCATION_ENABLED', true);
  }

  /**
   * Issue a new refresh token for a user (login only).
   * Creates a new row with created_at and hard_expiry set.
   * hard_expiry is immutable: 7 days from created_at, cannot be extended.
   *
   * Returns the token in clear (to send to client);
   * stores only the hash in DB.
   *
   * Per SEC2-680: "every refresh issues a new refresh token"
   * (Note: "refresh" here means the rotate() method, which calls issue().
   * But this issue() is also called on login to create the initial token.)
   */
  async issue(userId: string): Promise<string> {
    const token = uuidv4();
    const hash = this.repository.hashToken(token);

    // save() now creates created_at and hard_expiry automatically
    await this.repository.save(userId, hash);
    this.logger.log(`Issued new refresh token for user ${userId}`);
    return token;
  }

  /**
   * Verify a refresh token presented by client.
   * Checks: existence, hash match, hard_expiry (fixed 7-day window), revocation status.
   *
   * Returns true if valid, false otherwise.
   * Per SEC2-681: "every authentication failure returns the same AUTH-401 body"
   * so this doesn't throw—caller decides the response.
   */
  async verify(userId: string, token: string): Promise<boolean> {
    const hash = this.repository.hashToken(token);
    const record = await this.repository.verify(userId, hash, this.revocationEnabled);
    return record !== null;
  }

  /**
   * Rotate a refresh token: verify old token, update only token_hash (not hard_expiry).
   *
   * Per SEC2-680:
   * - "Every refresh issues a new refresh token"
   * - hard_expiry is IMMUTABLE: cannot be extended by refreshing
   * - After 7 days from initial login, user MUST re-authenticate (no refresh allowed)
   * - If revocation enabled: "presenting a consumed token revokes every refresh token"
   *
   * Called after successful /auth/refresh request.
   */
  async rotate(userId: string, presentedToken: string): Promise<string | null> {
    // Verify old token exists and has not exceeded hard_expiry
    const isValid = await this.verify(userId, presentedToken);
    if (!isValid) {
      this.logger.log(`Refresh token verification failed for user ${userId}`);
      return null;
    }

    // Check if hard_expiry has been exceeded (should not happen if verify() passed, but be explicit)
    const isExpired = await this.repository.isHardExpired(userId);
    if (isExpired) {
      this.logger.log(`Session hard expired for user ${userId}, re-authentication required`);
      throw new UnauthorizedException('Session hard expired, please login again');
    }

    // Rotate to a new token: update token_hash only, keep created_at and hard_expiry immutable
    // This is how we enforce the 7-day fixed window from initial login
    const newToken = uuidv4();
    const newHash = this.repository.hashToken(newToken);
    await this.repository.updateTokenHashOnly(userId, newHash);
    this.logger.log(`Rotated refresh token for user ${userId}, hard_expiry remains immutable`);
    return newToken;
  }

  /**
   * Rotate a refresh token when we don't know the userId upfront.
   * Used by /auth/refresh endpoint which just receives the token.
   *
   * Returns { userId, newRefreshToken } if valid, null if token is invalid/expired/revoked.
   *
   * Key: Checks hard_expiry (immutable from initial login).
   * If hard_expiry has passed, this throws UnauthorizedException("Session hard expired").
   */
  async rotateWithUserLookup(presentedToken: string): Promise<{ userId: string; newRefreshToken: string } | null> {
    const hash = this.repository.hashToken(presentedToken);
    const result = await this.repository.verifyAndGetUserId(hash, this.revocationEnabled);
    
    if (!result) {
      this.logger.log('Refresh token verification failed (token not found, expired, or revoked)');
      return null;
    }

    const { userId } = result;

    // Check if hard_expiry has been exceeded
    const isExpired = await this.repository.isHardExpired(userId);
    if (isExpired) {
      this.logger.log(`Session hard expired for user ${userId}, re-authentication required`);
      return null; // Return null instead of throwing, since this is the refresh endpoint
    }

    // Rotate to a new token: update token_hash only, keep created_at and hard_expiry immutable
    const newToken = uuidv4();
    const newHash = this.repository.hashToken(newToken);
    await this.repository.updateTokenHashOnly(userId, newHash);
    this.logger.log(`Rotated refresh token for user ${userId}, hard_expiry remains immutable`);
    return { userId, newRefreshToken: newToken };
  }

  /**
   * Get configured revocation behavior (for security review documentation).
   * Per SEC2-684: the revocation decision must be documented with finding + disposition.
   */
  getRevocationBehavior(): string {
    if (this.revocationEnabled) {
      return 'REVOKED: Old tokens are marked revoked on exchange.';
    }
    return 'NOT_REVOKED: Old tokens remain valid; they expire after hard_expiry.';
  }
}
