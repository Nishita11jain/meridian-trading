import { Logger } from '@nestjs/common';

// Keys that must never reach a log line in clear, wherever they appear in an
// object graph -- a password, a token, or the header that carries one.
// Matched case-insensitively and with separators stripped, so
// "accessToken", "access_token" and "ACCESS-TOKEN" are all caught by the
// same "accesstoken" entry.
const REDACTED_KEYS = new Set(['password', 'accesstoken', 'refreshtoken', 'token', 'authorization']);

const REDACTED = '[REDACTED]';

function normaliseKey(key: string): string {
  return key.toLowerCase().replace(/[_-]/g, '');
}

/**
 * Recursively redacts any value whose key matches REDACTED_KEYS, at any
 * nesting depth, in objects and arrays. Returns a new value; never mutates
 * the input, so a caller can still use the original object after logging it.
 */
export function redact(value: unknown, seen: WeakSet<object> = new WeakSet()): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => redact(item, seen));
  }

  if (value !== null && typeof value === 'object') {
    if (value instanceof Date || value instanceof RegExp) {
      return value;
    }
    if (seen.has(value)) {
      return '[CIRCULAR]';
    }
    seen.add(value);

    const result: Record<string, unknown> = {};
    for (const [key, val] of Object.entries(value)) {
      result[key] = REDACTED_KEYS.has(normaliseKey(key)) ? REDACTED : redact(val, seen);
    }
    return result;
  }

  return value;
}

/**
 * A drop-in replacement for @nestjs/common's Logger that redacts by key
 * name at any depth before a call reaches the console. Every log call in
 * this service that could plausibly receive user input, a request body or
 * an error object should go through this instead of `new Logger(...)`,
 * so that a password or token making it into a log call is a structural
 * impossibility rather than a matter of every call site remembering.
 */
export class RedactingLogger extends Logger {
  private redactArgs(args: unknown[]): unknown[] {
    return args.map((arg) => (typeof arg === 'object' && arg !== null ? redact(arg) : arg));
  }

  override log(message: unknown, ...optionalParams: unknown[]): void {
    super.log(
      typeof message === 'object' && message !== null ? redact(message) : message,
      ...this.redactArgs(optionalParams),
    );
  }

  override warn(message: unknown, ...optionalParams: unknown[]): void {
    super.warn(
      typeof message === 'object' && message !== null ? redact(message) : message,
      ...this.redactArgs(optionalParams),
    );
  }

  override error(message: unknown, ...optionalParams: unknown[]): void {
    super.error(
      typeof message === 'object' && message !== null ? redact(message) : message,
      ...this.redactArgs(optionalParams),
    );
  }

  override debug(message: unknown, ...optionalParams: unknown[]): void {
    super.debug(
      typeof message === 'object' && message !== null ? redact(message) : message,
      ...this.redactArgs(optionalParams),
    );
  }
}
