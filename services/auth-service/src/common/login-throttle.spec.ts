import { loginThrottleOptions } from './login-throttle.js';

describe('loginThrottleOptions', () => {
  it('converts the window from seconds to the milliseconds @nestjs/throttler expects', () => {
    const [options] = loginThrottleOptions(900, 5);

    expect(options.ttl).toBe(900_000);
    expect(options.limit).toBe(5);
    expect(options.keyPrefix).toBe('login');
  });

  it('accepts the strings an environment file produces', () => {
    const [options] = loginThrottleOptions('900', '5');

    expect(options.ttl).toBe(900_000);
    expect(options.limit).toBe(5);
  });

  it('the default window is fifteen minutes, not under a second', () => {
    const [options] = loginThrottleOptions(900, 5);

    expect(options.ttl).toBeGreaterThanOrEqual(15 * 60 * 1000);
  });

  it.each([0, -1, 1.5, 'abc', '', undefined, null, NaN])('refuses %p as the window rather than disabling the throttle', (bad) => {
    expect(() => loginThrottleOptions(bad, 5)).toThrow(/LOGIN_THROTTLE_TTL_SECONDS/);
  });

  it.each([0, -3, 2.5, 'many', undefined])('refuses %p as the limit', (bad) => {
    expect(() => loginThrottleOptions(900, bad)).toThrow(/LOGIN_THROTTLE_LIMIT/);
  });
});
