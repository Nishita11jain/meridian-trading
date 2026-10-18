import type { ExecutionContext } from '@nestjs/common';
import { UnauthorizedException } from '@nestjs/common';
import jwt from 'jsonwebtoken';
import { JwtAuthGuard } from './jwt-auth.guard.js';
import { TokenService, getJwtSecret, JWT_ISSUER } from './token.service.js';

function contextWithHeader(authorization?: string): ExecutionContext {
  const request: { headers: Record<string, string>; user?: unknown } = {
    headers: authorization ? { authorization } : {},
  };
  return {
    switchToHttp: () => ({ getRequest: () => request }),
  } as unknown as ExecutionContext;
}

describe('JwtAuthGuard', () => {
  const tokenService = new TokenService();
  const guard = new JwtAuthGuard(tokenService);
  const subject = { id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', accountId: 1, roles: ['CUSTOMER'] };

  it('accepts a valid token', () => {
    const token = tokenService.signAccessToken(subject);
    const context = contextWithHeader(`Bearer ${token}`);
    expect(guard.canActivate(context)).toBe(true);
  });

  it('refuses an expired token', () => {
    // A genuine token signed with the real key and issuer, but with an exp
    // in the past -- not a corrupted payload, which the signature check
    // would refuse first and would therefore pass against a guard with no
    // clock check at all.
    const expired = jwt.sign({ sub: subject.id, accountId: subject.accountId, roles: subject.roles, iss: JWT_ISSUER }, getJwtSecret(), {
      algorithm: 'HS256',
      expiresIn: -10,
    });
    const context = contextWithHeader(`Bearer ${expired}`);
    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });

  it('refuses a token with a wrong signature before any claim is read', () => {
    const wrongKeyToken = jwt.sign(
      { sub: subject.id, accountId: subject.accountId, roles: subject.roles, iss: JWT_ISSUER },
      'a-completely-different-signing-key-32-bytes-min',
      { algorithm: 'HS256', expiresIn: '15m' },
    );
    const context = contextWithHeader(`Bearer ${wrongKeyToken}`);
    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });

  it('refuses a token with the wrong issuer', () => {
    const wrongIssuer = jwt.sign(
      { sub: subject.id, accountId: subject.accountId, roles: subject.roles, iss: 'some-other-service' },
      getJwtSecret(),
      { algorithm: 'HS256', expiresIn: '15m' },
    );
    const context = contextWithHeader(`Bearer ${wrongIssuer}`);
    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });

  it('refuses a malformed header', () => {
    expect(() => guard.canActivate(contextWithHeader(undefined))).toThrow(UnauthorizedException);
    expect(() => guard.canActivate(contextWithHeader('not-a-bearer-token'))).toThrow(UnauthorizedException);
    expect(() => guard.canActivate(contextWithHeader('Bearer '))).toThrow(UnauthorizedException);
  });
});
