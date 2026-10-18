import { AuthService } from './auth.service.js';
import { DummyHashService } from './dummy-hash.js';
import { PasswordService } from '../users/password.service.js';
import { TokenService } from '../tokens/token.service.js';
import { RefreshTokenService } from '../tokens/refresh-token.service.js';
import type { RefreshTokenRepository } from '../tokens/refresh-token.repository.js';

/**
 * SEC2-681: One Answer for Every Failed Login
 *
 * DummyHashService's own behaviour (generation, caching, the uninitialised
 * guard) is unit-tested directly below. The "same response" and "comparable
 * timing" paths are exercised against the real AuthService.login(), not
 * against arithmetic on hardcoded constants.
 *
 * NOTE on the throttle path: this suite deliberately does not import
 * AuthController or anything from '@nestjs/throttler'. The installed
 * @nestjs/throttler@6.7.0 ships a CommonJS build that does
 * `require('@nestjs/common')`, but the installed @nestjs/common@12.0.4 is
 * ESM-only ("type": "module", no CJS entry point) -- so importing either
 * package here crashes Jest with "Must use import to load ES Module:
 * .../@nestjs/common/index.js" (the same failure that broke
 * test/app.e2e-spec.ts, which also pulls in ThrottlerModule transitively).
 * This is a real dependency-version mismatch, not a test-authoring issue,
 * and it's written up separately rather than worked around here. The
 * throttle test below is `it.skip` with the same explanation, so it shows
 * up as skipped rather than silently missing or falsely green.
 */
describe('SEC2-681: One Answer for Every Failed Login', () => {
  describe('DummyHashService initialization', () => {
    let dummyHashService: DummyHashService;

    beforeEach(async () => {
      dummyHashService = new DummyHashService();
      await dummyHashService.onModuleInit();
    });

    it('should generate dummy hash on module init', () => {
      const dummyHash = dummyHashService.getDummyHash();
      expect(dummyHash).toBeDefined();
      expect(dummyHash).toMatch(/^\$argon2id\$/);
    });

    it('should cache the dummy hash (same instance on multiple calls)', () => {
      const hash1 = dummyHashService.getDummyHash();
      const hash2 = dummyHashService.getDummyHash();
      expect(hash1).toBe(hash2);
    });

    it('should throw if getDummyHash is called before initialization', () => {
      const uninitialisedService = new DummyHashService();
      expect(() => uninitialisedService.getDummyHash()).toThrow('DummyHashService not initialized');
    });
  });

  describe('login failure (SEC2-681 acceptance criteria)', () => {
    const KNOWN_USERNAME = 'priya.menon';
    const KNOWN_PASSWORD = 'correct horse battery staple';
    const knownUser = {
      id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
      username: KNOWN_USERNAME,
      accountId: 1,
      roles: ['CUSTOMER'],
      createdOn: new Date().toISOString(),
      passwordHash: '',
    };

    let authService: AuthService;

    beforeAll(async () => {
      const passwordService = new PasswordService();
      knownUser.passwordHash = await passwordService.hash(KNOWN_PASSWORD);

      const dummyHashService = new DummyHashService();
      await dummyHashService.onModuleInit();

      const usersRepository = {
        findByUsername: async (username: string) => (username === KNOWN_USERNAME ? knownUser : null),
        findById: async (id: string) => (id === knownUser.id ? knownUser : null),
      };

      const refreshTokenService = new RefreshTokenService(
        { hashToken: () => '', save: async () => undefined, verify: async () => null } as unknown as RefreshTokenRepository,
        { get: (_key: string, fallback: unknown) => fallback } as any,
      );

      authService = new AuthService(
        usersRepository as any,
        passwordService,
        {} as any,
        new TokenService(),
        refreshTokenService,
        dummyHashService,
      );
    });

    async function attemptLogin(username: string, password: string) {
      const start = process.hrtime.bigint();
      const result = await authService.login(username, password);
      const elapsedMs = Number(process.hrtime.bigint() - start) / 1_000_000;
      return { result, elapsedMs };
    }

    it('an unknown user and a wrong password return the same result', async () => {
      const unknownUser = await attemptLogin('no-such-user', 'irrelevant-password-value');
      const wrongPassword = await attemptLogin(KNOWN_USERNAME, 'definitely-the-wrong-password');

      // AuthService.login() returns null on every failure; AuthController's
      // only branch on that result (auth.controller.ts) is unconditional --
      // `if (!tokens) throw new HttpException({errorCode:'AUTH-401',
      // message:'Unauthorised'}, 401)` -- so a null from both paths here is
      // exactly what produces the identical AUTH-401 envelope and status at
      // the HTTP layer for both cases, with nothing left to differ.
      expect(unknownUser.result).toBeNull();
      expect(wrongPassword.result).toBeNull();
    });

    it('neither path returns before doing comparable work', async () => {
      // A single sample of an argon2id verification is noisy (scheduler
      // jitter, GC pauses) -- average several runs of each path rather than
      // comparing two one-shot timings, so the assertion tracks the real
      // gap an early return would leave (unknown-user returning in ~1ms
      // against a ~50-150ms hash verification is two orders of magnitude,
      // not a coin-flip around a tight ratio).
      const SAMPLES = 5;
      const unknownTimes: number[] = [];
      const wrongPasswordTimes: number[] = [];
      for (let i = 0; i < SAMPLES; i++) {
        unknownTimes.push((await attemptLogin(`unknown-user-${i}`, 'irrelevant-password-value')).elapsedMs);
        wrongPasswordTimes.push((await attemptLogin(KNOWN_USERNAME, `wrong-password-${i}`)).elapsedMs);
      }
      const average = (values: number[]) => values.reduce((sum, v) => sum + v, 0) / values.length;
      const unknownAvg = average(unknownTimes);
      const wrongPasswordAvg = average(wrongPasswordTimes);

      // Both paths verify an argon2id hash with the same cost parameters
      // (PasswordService / DummyHashService), so neither should be a cheap
      // early return -- a near-instant average here would mean the
      // dummy-hash verification was skipped.
      expect(unknownAvg).toBeGreaterThan(20);
      expect(wrongPasswordAvg).toBeGreaterThan(20);

      // Per the sprint's own guidance: a generous absolute threshold is
      // fair (machine load moves both by tens of milliseconds), but one
      // path taking several times as long as the other on average is an
      // early return, not noise. 3x leaves headroom for scheduler jitter
      // while still catching an early return by a wide margin (which would
      // show up as 50-100x, not 3x).
      const [slower, faster] = [unknownAvg, wrongPasswordAvg].sort((a, b) => b - a);
      expect(slower).toBeLessThan(faster * 3);
    }, 15000);
  });

  describe('login throttle (SEC2-681 acceptance criteria)', () => {
    // Blocked: see the file-level note above. Exercising the real
    // ThrottlerGuard requires importing '@nestjs/throttler', which crashes
    // under this project's installed @nestjs/common@12 (ESM-only) -- a
    // dependency-version mismatch outside this fix's scope. Written up
    // separately rather than worked around here.
    it.skip('the throttle limits repeated failed attempts from one caller -- BLOCKED: @nestjs/throttler is incompatible with the installed @nestjs/common@12 (ESM-only); importing it crashes Jest. See file-level comment.', () => {
      // Intentionally left unimplemented.
    });
  });
});
