import { jest } from '@jest/globals';
import { HttpException } from '@nestjs/common';

// Same stand-in as auth.controller.spec.ts: the installed CommonJS @nestjs/throttler cannot load under
// Jest against the ESM-only @nestjs/common@12, and the guard only decorates the login route. The real
// AuthController is imported after the mock is registered, so everything asserted below is real code.
jest.unstable_mockModule('@nestjs/throttler', () => ({
  ThrottlerGuard: class {
    canActivate() {
      return true;
    }
  },
}));
const { AuthController } = await import('./auth.controller.js');

/**
 * What a caller of the HTTP routes sees, for register, login, refresh and /auth/me: the success
 * bodies, and the status and `{errorCode, message}` envelope of each refusal.
 */
function build() {
  const authService = {
    register: jest.fn(async (_body: any): Promise<any> => ({ id: 'u-1', username: 'priya.menon', accountId: 7, roles: ['CUSTOMER'] })),
    login: jest.fn(async (_u: string, _p: string): Promise<any> => ({ accessToken: 'a', refreshToken: 'r', tokenType: 'Bearer', expiresIn: 900 })),
    refresh: jest.fn(async (_t: string): Promise<any> => ({ accessToken: 'a2', refreshToken: 'r2', tokenType: 'Bearer', expiresIn: 900 })),
    getUserById: jest.fn(async (_id: string): Promise<any> => ({ id: 'u-1', username: 'priya.menon', accountId: 7, roles: ['CUSTOMER'] })),
  };
  return { authService, controller: new AuthController(authService as any) };
}

async function refusal(call: () => Promise<unknown>) {
  try {
    await call();
  } catch (err) {
    expect(err).toBeInstanceOf(HttpException);
    const http = err as HttpException;
    return { status: http.getStatus(), body: http.getResponse() };
  }
  throw new Error('the route answered instead of refusing');
}

describe('AuthController.register', () => {
  const body = { username: 'priya.menon', password: 'correct horse battery staple', accountId: 7 };

  it('returns the created user', async () => {
    const { controller, authService } = build();

    const user = await controller.register(body as any);

    expect(user).toMatchObject({ username: 'priya.menon', accountId: 7, roles: ['CUSTOMER'] });
    expect(authService.register).toHaveBeenCalledWith(body);
  });

  it('answers 409 AUTH-409 for a taken username or a linked account, with a message that names neither', async () => {
    const { controller, authService } = build();
    authService.register.mockRejectedValue(Object.assign(new Error('Conflict'), { code: 'AUTH-409' }));

    const result = await refusal(() => controller.register(body as any));

    expect(result.status).toBe(409);
    expect(result.body).toEqual({ errorCode: 'AUTH-409', message: 'Registration failed' });
  });

  it('answers 422 VAL-422 when the account cannot be used', async () => {
    const { controller, authService } = build();
    authService.register.mockRejectedValue(Object.assign(new Error('not usable'), { code: 'VAL-422' }));

    const result = await refusal(() => controller.register(body as any));

    expect(result.status).toBe(422);
    expect(result.body).toEqual({ errorCode: 'VAL-422', message: 'Invalid input' });
  });

  it('answers 422 VAL-422 for any other failure, without leaking the reason', async () => {
    const { controller, authService } = build();
    authService.register.mockRejectedValue(new Error('relation "auth.users" does not exist'));

    const result = await refusal(() => controller.register(body as any));

    expect(result.status).toBe(422);
    expect(JSON.stringify(result.body)).not.toContain('auth.users');
  });
});

describe('AuthController.login', () => {
  it('returns the tokens for good credentials', async () => {
    const { controller } = build();

    await expect(controller.login({ username: 'priya.menon', password: 'pw' })).resolves.toMatchObject({
      tokenType: 'Bearer',
      expiresIn: 900,
    });
  });
});

describe('AuthController.refresh', () => {
  it('returns the new tokens', async () => {
    const { controller, authService } = build();

    const tokens = await controller.refresh({ refreshToken: 'r' });

    expect(tokens).toMatchObject({ accessToken: 'a2', refreshToken: 'r2' });
    expect(authService.refresh).toHaveBeenCalledWith('r');
  });

  it('answers 401 AUTH-401 for a refresh token that no longer works', async () => {
    const { controller, authService } = build();
    authService.refresh.mockResolvedValue(null);

    const result = await refusal(() => controller.refresh({ refreshToken: 'stale' }));

    expect(result.status).toBe(401);
    expect(result.body).toEqual({ errorCode: 'AUTH-401', message: 'Unauthorised' });
  });
});

describe('AuthController.me', () => {
  const claims = { sub: 'u-1', accountId: 7, roles: ['CUSTOMER'], iss: 'auth-service', iat: 1, exp: 2 } as any;

  it('returns the profile of the user the token names', async () => {
    const { controller, authService } = build();

    const profile = await controller.me(claims);

    expect(profile).toMatchObject({ username: 'priya.menon', accountId: 7 });
    expect(authService.getUserById).toHaveBeenCalledWith('u-1');
  });

  it('answers 401 AUTH-401 when a valid token names a user who no longer exists', async () => {
    const { controller, authService } = build();
    authService.getUserById.mockResolvedValue(null);

    const result = await refusal(() => controller.me(claims));

    expect(result.status).toBe(401);
    expect(result.body).toEqual({ errorCode: 'AUTH-401', message: 'Unauthorised' });
  });
});
