/**
 * The login throttle, from two settings written in human units.
 *
 * `@nestjs/throttler` v6 reads `ttl` in MILLISECONDS. LOGIN_THROTTLE_TTL_SECONDS is in seconds, so
 * passing it straight through (as `ttl: 900`) made the "15 minute" window 900 ms long: the limit
 * of 5 cleared itself in about a second and throttled nothing. Convert here, in one place.
 *
 * Settings read from the environment arrive as strings, so they are parsed, and a value that is
 * not a positive whole number stops the service at start-up instead of silently disabling the
 * throttle.
 */
export interface LoginThrottleOptions {
  ttl: number;
  limit: number;
  keyPrefix: string;
}

function positiveWholeNumber(name: string, value: unknown): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive whole number, got ${JSON.stringify(value)}`);
  }
  return parsed;
}

export function loginThrottleOptions(ttlSeconds: unknown, limit: unknown): LoginThrottleOptions[] {
  return [
    {
      ttl: positiveWholeNumber('LOGIN_THROTTLE_TTL_SECONDS', ttlSeconds) * 1000,
      limit: positiveWholeNumber('LOGIN_THROTTLE_LIMIT', limit),
      keyPrefix: 'login',
    },
  ];
}
