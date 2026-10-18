import { jest } from '@jest/globals';
import { AuthService } from './auth.service.js';
import { AccountAlreadyLinkedError, UsernameTakenError } from '../users/users.repository.js';

/**
 * AuthService, driven for real, with its collaborators replaced by stand-ins: registration, a
 * successful login, refresh, and the user lookup behind /auth/me. Each flow has its positive case
 * and the negative cases that matter (taken username, unusable account, wrong password, a token
 * that no longer works, a user who has gone).
 */
function build() {
  const user = {
    id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    username: 'priya.menon',
    passwordHash: '$argon2id$stored-hash',
    accountId: 7,
    roles: ['CUSTOMER'],
    createdOn: new Date('2026-01-01T00:00:00Z'),
  };

  const usersRepository = {
    create: jest.fn(async (u: any) => ({ ...u, createdOn: user.createdOn })),
    findByUsername: jest.fn(async (_name: string): Promise<any> => user),
    findById: jest.fn(async (_id: string): Promise<any> => user),
  };
  const passwordService = {
    hash: jest.fn(async (plain: string) => `hashed(${plain})`),
    verify: jest.fn(async (_hash: string, _plain: string) => true),
  };
  const accountValidation = { isUsable: jest.fn(async (_id: number) => true) };
  const tokenService = { signAccessToken: jest.fn((_u: any) => 'signed.access.token') };
  const refreshTokenService = {
    issue: jest.fn(async (_id: string) => 'refresh-token-1'),
    rotateWithUserLookup: jest.fn(async (_token: string): Promise<any> => ({ userId: user.id, newRefreshToken: 'refresh-token-2' })),
  };
  const dummyHashService = { getDummyHash: jest.fn(() => '$argon2id$dummy') };

  const service = new AuthService(
    usersRepository as any,
    passwordService as any,
    accountValidation as any,
    tokenService as any,
    refreshTokenService as any,
    dummyHashService as any,
  );
  return { service, user, usersRepository, passwordService, accountValidation, tokenService, refreshTokenService, dummyHashService };
}

describe('AuthService.register', () => {
  const request = { username: 'priya.menon', password: 'correct horse battery staple', accountId: 7 };

  it('creates the user and returns the profile, with no tokens', async () => {
    const { service } = build();

    const result: any = await service.register(request);

    expect(result).toEqual({
      id: expect.any(String),
      username: 'priya.menon',
      accountId: 7,
      roles: ['CUSTOMER'],
      createdOn: new Date('2026-01-01T00:00:00Z'),
    });
    expect(result.accessToken).toBeUndefined();
    expect(result.refreshToken).toBeUndefined();
  });

  it('checks the account is usable before anything is written', async () => {
    const { service, accountValidation, usersRepository } = build();

    await service.register(request);

    expect(accountValidation.isUsable).toHaveBeenCalledWith(7);
    expect(accountValidation.isUsable.mock.invocationCallOrder[0]).toBeLessThan(usersRepository.create.mock.invocationCallOrder[0]);
  });

  it('stores a hash of the password, never the password itself', async () => {
    const { service, usersRepository } = build();

    await service.register(request);

    const stored = usersRepository.create.mock.calls[0][0];
    expect(stored.passwordHash).toBe('hashed(correct horse battery staple)');
    expect(JSON.stringify(stored)).not.toContain('"correct horse battery staple"');
  });

  it('ignores a role the caller asks for: every self-registration is a CUSTOMER', async () => {
    const { service, usersRepository } = build();

    await service.register({ ...request, roles: ['ADMIN'] } as any);

    expect(usersRepository.create.mock.calls[0][0].roles).toEqual(['CUSTOMER']);
  });

  it('refuses an account that is not usable (VAL-422) and creates nothing', async () => {
    const { service, accountValidation, usersRepository, passwordService } = build();
    accountValidation.isUsable.mockResolvedValue(false);

    await expect(service.register(request)).rejects.toMatchObject({ code: 'VAL-422' });

    expect(usersRepository.create).not.toHaveBeenCalled();
    expect(passwordService.hash).not.toHaveBeenCalled();
  });

  it('reports a taken username as AUTH-409', async () => {
    const { service, usersRepository } = build();
    usersRepository.create.mockRejectedValue(new UsernameTakenError());

    await expect(service.register(request)).rejects.toMatchObject({ code: 'AUTH-409' });
  });

  it('reports an account that is already linked to a user as AUTH-409', async () => {
    const { service, usersRepository } = build();
    usersRepository.create.mockRejectedValue(new AccountAlreadyLinkedError());

    await expect(service.register(request)).rejects.toMatchObject({ code: 'AUTH-409' });
  });

  it('does not disguise an unexpected failure as a conflict', async () => {
    const { service, usersRepository } = build();
    const failure = new Error('connection to the database was lost');
    usersRepository.create.mockRejectedValue(failure);

    await expect(service.register(request)).rejects.toBe(failure);
  });
});

describe('AuthService.login (success path)', () => {
  it('issues a bearer access token and a refresh token that last 15 minutes', async () => {
    const { service, user, tokenService, refreshTokenService } = build();

    const tokens = await service.login('priya.menon', 'correct horse battery staple');

    expect(tokens).toEqual({
      accessToken: 'signed.access.token',
      refreshToken: 'refresh-token-1',
      tokenType: 'Bearer',
      expiresIn: 900,
    });
    expect(tokenService.signAccessToken).toHaveBeenCalledWith(user);
    expect(refreshTokenService.issue).toHaveBeenCalledWith(user.id);
  });

  it('a wrong password gets nothing: no access token and no refresh token', async () => {
    const { service, passwordService, tokenService, refreshTokenService } = build();
    passwordService.verify.mockResolvedValue(false);

    await expect(service.login('priya.menon', 'wrong password')).resolves.toBeNull();

    expect(tokenService.signAccessToken).not.toHaveBeenCalled();
    expect(refreshTokenService.issue).not.toHaveBeenCalled();
  });

  it('an unknown user gets nothing, after the same password check a real user would cost', async () => {
    const { service, usersRepository, passwordService, dummyHashService, refreshTokenService } = build();
    usersRepository.findByUsername.mockResolvedValue(null);

    await expect(service.login('nobody', 'whatever')).resolves.toBeNull();

    expect(passwordService.verify).toHaveBeenCalledWith(dummyHashService.getDummyHash(), 'whatever');
    expect(refreshTokenService.issue).not.toHaveBeenCalled();
  });
});

describe('AuthService.refresh', () => {
  it('rotates the refresh token and issues a new access token for the same user', async () => {
    const { service, user, tokenService } = build();

    const tokens = await service.refresh('refresh-token-1');

    expect(tokens).toEqual({
      accessToken: 'signed.access.token',
      refreshToken: 'refresh-token-2',
      tokenType: 'Bearer',
      expiresIn: 900,
    });
    expect(tokenService.signAccessToken).toHaveBeenCalledWith(user);
  });

  it('refuses a refresh token that is unknown, used, expired or revoked', async () => {
    const { service, refreshTokenService, usersRepository, tokenService } = build();
    refreshTokenService.rotateWithUserLookup.mockResolvedValue(null);

    await expect(service.refresh('stale-token')).resolves.toBeNull();

    expect(usersRepository.findById).not.toHaveBeenCalled();
    expect(tokenService.signAccessToken).not.toHaveBeenCalled();
  });

  it('refuses when the user was deleted after the token was issued', async () => {
    const { service, usersRepository, tokenService } = build();
    usersRepository.findById.mockResolvedValue(null);

    await expect(service.refresh('refresh-token-1')).resolves.toBeNull();

    expect(tokenService.signAccessToken).not.toHaveBeenCalled();
  });
});

describe('AuthService.getUserById (behind /auth/me)', () => {
  it('returns the profile without the password hash', async () => {
    const { service, user } = build();

    const profile: any = await service.getUserById(user.id);

    expect(profile).toEqual({
      id: user.id,
      username: 'priya.menon',
      accountId: 7,
      roles: ['CUSTOMER'],
      createdOn: user.createdOn,
    });
    expect(profile.passwordHash).toBeUndefined();
    expect(JSON.stringify(profile)).not.toContain('argon2');
  });

  it('returns null for a user that no longer exists', async () => {
    const { service, usersRepository } = build();
    usersRepository.findById.mockResolvedValue(null);

    await expect(service.getUserById('gone')).resolves.toBeNull();
  });
});
