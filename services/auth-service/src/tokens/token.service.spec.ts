import jwt from 'jsonwebtoken';
import { TokenService, getJwtSecret, JWT_ISSUER } from './token.service.js';

describe('TokenService', () => {
  const service = new TokenService();
  const subject = { id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', accountId: 1, roles: ['CUSTOMER'] };

  it('issues a token with exactly the contract claims and expiry', () => {
    const token = service.signAccessToken(subject);
    const decoded = jwt.decode(token) as Record<string, unknown>;

    expect(Object.keys(decoded).sort()).toEqual(['accountId', 'exp', 'iat', 'iss', 'roles', 'sub'].sort());
    expect(decoded.sub).toBe(subject.id);
    expect(decoded.accountId).toBe(subject.accountId);
    expect(decoded.roles).toEqual(subject.roles);
    expect(decoded.iss).toBe(JWT_ISSUER);
    expect(typeof decoded.iat).toBe('number');
    expect(typeof decoded.exp).toBe('number');
    expect((decoded.exp as number) - (decoded.iat as number)).toBe(15 * 60);
  });

  it('signs with the service key, verifiable against it', () => {
    const token = service.signAccessToken(subject);
    expect(() => jwt.verify(token, getJwtSecret(), { algorithms: ['HS256'] })).not.toThrow();
  });

  it('carries no claim outside the contract set', () => {
    const token = service.signAccessToken(subject);
    const decoded = jwt.decode(token) as Record<string, unknown>;
    const allowedClaims = new Set(['sub', 'accountId', 'roles', 'iat', 'exp', 'iss']);
    for (const claim of Object.keys(decoded)) {
      expect(allowedClaims.has(claim)).toBe(true);
    }
  });
});
