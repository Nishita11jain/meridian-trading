import { Injectable } from '@nestjs/common';
import jwt from 'jsonwebtoken';
import { RedactingLogger } from '../common/redacting-logger.js';

const REQUEST_TIMEOUT_MS = 5000;

/**
 * Confirms an accountId is real and tradeable before letting registration
 * link a login to it.
 *
 * The auth schema never reads the trading schema directly (no FK, no cross-
 * schema SELECT -- see the note in 001_init_auth_schema.sql), so existence
 * is checked the same way any other client would: by calling the Trade REST
 * API's own GET /api/v1/accounts/{id}, which already knows how to answer
 * "does this account exist and is it active" (ACC-404 / ACC-403).
 *
 * That endpoint is authenticated and only ever returns the account named by
 * the caller's own token (trade-api-service's AccountController compares
 * the path id against the token's accountId claim and refuses a mismatch).
 * Since this service is the one thing that can mint a valid token, it signs
 * a short-lived, single-purpose one carrying exactly the accountId being
 * checked, uses it once for this one request, and discards it -- it is
 * never returned to any caller of this service.
 */
@Injectable()
export class AccountValidationService {
  private readonly logger = new RedactingLogger(AccountValidationService.name);

  async isUsable(accountId: number): Promise<boolean> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      // Read lazily, at the point of use, not as a top-level module
      // constant -- see the matching comment in token.service.ts's
      // getJwtSecret() for why a top-level read here would race
      // ConfigModule.forRoot() and lose, picking up `undefined` before
      // .env is ever loaded.
      const jwtSecret = process.env.JWT_SECRET as string;
      const checkToken = jwt.sign(
        { sub: 'auth-service-internal-check', accountId, roles: ['CUSTOMER'], iss: 'auth-service' },
        jwtSecret,
        { expiresIn: '30s' },
      );
      // Read lazily for the same reason as JWT_SECRET above; no default URL, it must be set in .env.
      const url = `${process.env.TRADE_API_BASE_URL}/api/v1/accounts/${accountId}`;
      const response = await fetch(url, {
        headers: { Authorization: `Bearer ${checkToken}` },
        signal: controller.signal,
      });
      if (!response.ok) {
        this.logger.warn(`Account check for id=${accountId} -> ${response.status} at ${url}`);
      }
      return response.ok;
    } catch (err) {
      // Reachability failure (DNS, connection refused, timeout) is logged
      // and treated the same as "not usable" -- this endpoint has no way to
      // distinguish "trade-api-service is down" from "invalid account" for
      // the caller, and per the current design decision, that's accepted.
      this.logger.error(`Account check for id=${accountId} failed: ${(err as Error).message}`);
      return false;
    } finally {
      clearTimeout(timeout);
    }
  }
}
