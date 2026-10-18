import { jest } from '@jest/globals';
import { UnauthorizedException } from '@nestjs/common';
import type { ConfigService } from '@nestjs/config';
import { RefreshTokenService } from './refresh-token.service.js';

/**
 * RefreshTokenService, the real class over a stand-in repository: issuing a token, checking one,
 * and rotating it by user id (the path with the hard-expiry exception), with revocation both on
 * and off. The refresh endpoint's own path, rotateWithUserLookup, is in refresh-token.service.spec.ts.
 */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const userId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

function build(revocationEnabled = true) {
  const repository = {
    hashToken: jest.fn((token: string) => `hash(${token})`),
    save: jest.fn(async (_id: string, _hash: string) => undefined),
    verify: jest.fn(async (_id: string, _hash: string, _revocation: boolean): Promise<any> => ({ id: 'row' })),
    isHardExpired: jest.fn(async (_id: string) => false),
    updateTokenHashOnly: jest.fn(async (_id: string, _hash: string) => undefined),
  };
  const config = { get: jest.fn((_key: string, _fallback?: unknown) => revocationEnabled) } as unknown as ConfigService;
  return { repository, service: new RefreshTokenService(repository as any, config) };
}

describe('RefreshTokenService.issue', () => {
  it('returns a random token and stores only its hash', async () => {
    const { service, repository } = build();

    const token = await service.issue(userId);

    expect(token).toMatch(UUID);
    expect(repository.save).toHaveBeenCalledWith(userId, `hash(${token})`);
    expect(repository.save.mock.calls[0][1]).not.toBe(token);
  });

  it('gives a different token every time', async () => {
    const { service } = build();

    expect(await service.issue(userId)).not.toBe(await service.issue(userId));
  });
});

describe('RefreshTokenService.verify', () => {
  it('accepts a token whose hash is stored, asking the repository to honour revocation', async () => {
    const { service, repository } = build(true);

    await expect(service.verify(userId, 'presented')).resolves.toBe(true);

    expect(repository.verify).toHaveBeenCalledWith(userId, 'hash(presented)', true);
  });

  it('refuses a token that is unknown, revoked, or past its hard expiry', async () => {
    const { service, repository } = build();
    repository.verify.mockResolvedValue(null);

    await expect(service.verify(userId, 'presented')).resolves.toBe(false);
  });

  it('does not ask for revocation to be honoured when it is switched off', async () => {
    const { service, repository } = build(false);

    await service.verify(userId, 'presented');

    expect(repository.verify).toHaveBeenCalledWith(userId, 'hash(presented)', false);
  });
});

describe('RefreshTokenService.rotate', () => {
  it('replaces the stored hash with a new token and returns that token', async () => {
    const { service, repository } = build();

    const rotated = await service.rotate(userId, 'presented');

    expect(rotated).toMatch(UUID);
    expect(rotated).not.toBe('presented');
    expect(repository.updateTokenHashOnly).toHaveBeenCalledWith(userId, `hash(${rotated})`);
  });

  it('refuses a token that does not verify, and changes nothing', async () => {
    const { service, repository } = build();
    repository.verify.mockResolvedValue(null);

    await expect(service.rotate(userId, 'bogus')).resolves.toBeNull();

    expect(repository.updateTokenHashOnly).not.toHaveBeenCalled();
  });

  it('refuses once the seven-day window has ended, with a clear error, and changes nothing', async () => {
    const { service, repository } = build();
    repository.isHardExpired.mockResolvedValue(true);

    const attempt = service.rotate(userId, 'presented');

    await expect(attempt).rejects.toBeInstanceOf(UnauthorizedException);
    await expect(attempt).rejects.toThrow('Session hard expired');
    expect(repository.updateTokenHashOnly).not.toHaveBeenCalled();
  });
});

describe('RefreshTokenService.getRevocationBehavior', () => {
  it('says old tokens are revoked when revocation is on', () => {
    expect(build(true).service.getRevocationBehavior()).toMatch(/^REVOKED/);
  });

  it('says old tokens simply expire when revocation is off', () => {
    expect(build(false).service.getRevocationBehavior()).toMatch(/^NOT_REVOKED/);
  });
});
