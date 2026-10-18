import { jest } from '@jest/globals';
import { HttpException } from '@nestjs/common';
import type { AuthService } from './auth.service.js';

// auth.controller.ts imports ThrottlerGuard from '@nestjs/throttler', whose
// installed CommonJS build cannot load under Jest against the ESM-only
// @nestjs/common@12 (the same mismatch sec2-681.spec.ts documents). The
// guard only decorates the route; it plays no part in how login() turns a
// failed lookup into a status and body, which is all this file tests. So
// that one package is replaced by an inert stand-in, and the real
// AuthController is imported after the mock is registered. Everything the
// test asserts on -- the controller's own login() -- is the real code.
jest.unstable_mockModule('@nestjs/throttler', () => ({
  ThrottlerGuard: class {
    canActivate() {
      return true;
    }
  },
}));
const { AuthController } = await import('./auth.controller.js');

/**
 * SEC2-681: "an unknown user and a wrong password return the same status
 * and body."
 *
 * Status and body are HTTP concepts, so this drives AuthController.login()
 * itself -- the code that turns AuthService.login()'s `null` into a 401
 * HttpException (auth.controller.ts: `if (!tokens) throw new HttpException(
 * { errorCode: 'AUTH-401', message: 'Unauthorised' }, 401)`). The
 * service-level half (both paths do comparable work and both resolve to
 * null) lives in sec2-681.spec.ts.
 *
 * The throttle limit itself is NOT covered here; that path stays skipped in
 * sec2-681.spec.ts.
 *
 * The service is stubbed the way AuthService.login() really behaves on
 * failure: it resolves to null both when the username is unknown and when
 * the password is wrong, so the stub cannot help the two cases differ --
 * only the controller decides what the caller sees.
 */
describe('SEC2-681: AuthController.login failure response', () => {
  const KNOWN_USERNAME = 'priya.menon';

  const authService = {
    login: async (_identifier: string, _password: string) => null,
  } as unknown as AuthService;
  const controller = new AuthController(authService);

  async function failedLogin(username: string, password: string) {
    try {
      await controller.login({ username, password });
    } catch (err) {
      expect(err).toBeInstanceOf(HttpException);
      const http = err as HttpException;
      return { status: http.getStatus(), body: http.getResponse() };
    }
    throw new Error('login() resolved instead of refusing the request');
  }

  it('an unknown user and a wrong password return the same status and body', async () => {
    const unknownUser = await failedLogin('no-such-user', 'irrelevant-password-value');
    const wrongPassword = await failedLogin(KNOWN_USERNAME, 'definitely-the-wrong-password');

    // The point: a caller cannot tell the two apart.
    expect(unknownUser).toEqual(wrongPassword);

    // And what they both get is the contract's 401 envelope.
    expect(unknownUser.status).toBe(401);
    expect(unknownUser.body).toEqual({ errorCode: 'AUTH-401', message: 'Unauthorised' });
  });
});
