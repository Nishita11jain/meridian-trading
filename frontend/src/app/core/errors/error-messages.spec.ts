import { describe, it, expect } from 'vitest';
import { HttpErrorResponse } from '@angular/common/http';
import { messageFor } from './error-messages';
import { ErrorResponse as TradeErrorResponse } from '../../clients/trade-client/model/errorResponse';
import { ErrorResponse as AuthErrorResponse } from '../../clients/auth-client/model/errorResponse';

describe('Error Messages Catalogue', () => {
  /**
   * Test 1: Every code in BOTH generated enums maps to a non-fallback message
   * Iterate all codes from both ErrorCodeEnum and verify each returns a specific message.
   */
  it('should map every code in both generated ErrorCodeEnum to a non-fallback message', () => {
    const tradeCodesArray = Object.values(TradeErrorResponse.ErrorCodeEnum);
    const authCodesArray = Object.values(AuthErrorResponse.ErrorCodeEnum);
    const allCodes = [...new Set([...tradeCodesArray, ...authCodesArray])];

    allCodes.forEach((code) => {
      const error = new HttpErrorResponse({
        status: 400,
        error: { errorCode: code, message: 'Server message' }
      });
      const message = messageFor(error);

      // Should not be the fallback message
      expect(message).not.toBe('Something went wrong. Please try again.');
      // Should not be the network error message
      expect(message).not.toContain('could not reach the service');
      // Should be non-empty
      expect(message.length).toBeGreaterThan(0);
    });
  });

  /**
   * Test 2: All 9 codes are covered (union has exactly 9 distinct codes; MKT-409 added for closed markets)
   */
  it('should have exactly 9 distinct error codes across both enums', () => {
    const tradeCodesArray = Object.values(TradeErrorResponse.ErrorCodeEnum);
    const authCodesArray = Object.values(AuthErrorResponse.ErrorCodeEnum);
    const allCodes = new Set([...tradeCodesArray, ...authCodesArray]);

    expect(allCodes.size).toBe(9);
    expect(Array.from(allCodes)).toContain('AUTH-401');
    expect(Array.from(allCodes)).toContain('AUTH-409');
    expect(Array.from(allCodes)).toContain('ACC-404');
    expect(Array.from(allCodes)).toContain('ACC-403');
    expect(Array.from(allCodes)).toContain('INS-404');
    expect(Array.from(allCodes)).toContain('ORD-400');
    expect(Array.from(allCodes)).toContain('ORD-409');
    expect(Array.from(allCodes)).toContain('VAL-422');
    expect(Array.from(allCodes)).toContain('MKT-409');
  });

  /**
   * Test 3: An unknown errorCode returns the fallback sentence
   */
  it('should return fallback for unknown errorCode', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: { errorCode: 'UNKNOWN-999', message: 'Unknown error' }
    });
    const message = messageFor(error);

    expect(message).toBe('Something went wrong. Please try again.');
    expect(message).not.toContain('UNKNOWN');
  });

  /**
   * A throttled sign-in (429) is told to wait, not shown as a generic failure
   */
  it('should tell the user to wait when the Auth service throttles sign-in (429)', () => {
    const error = new HttpErrorResponse({
      status: 429,
      error: { errorCode: 'RATE-429', message: 'Too many requests' }
    });
    const message = messageFor(error);

    expect(message).toContain('Too many attempts');
    expect(message).not.toBe('Something went wrong. Please try again.');
    expect(message).not.toContain('Too many requests');
  });

  /**
   * Test 4: status 0 returns the "could not reach the service" sentence
   */
  it('should return network error message for status 0', () => {
    const error = new HttpErrorResponse({
      status: 0,
      error: null
    });
    const message = messageFor(error);

    expect(message).toContain('could not reach the service');
    expect(message).not.toBe('Something went wrong. Please try again.');
  });

  /**
   * Test 5: Non-JSON body (string), null, undefined, and {} all return the fallback
   */
  it('should return fallback for string error', () => {
    const message = messageFor('string error');
    expect(message).toBe('Something went wrong. Please try again.');
  });

  it('should return fallback for null error', () => {
    const message = messageFor(null);
    expect(message).toBe('Something went wrong. Please try again.');
  });

  it('should return fallback for undefined error', () => {
    const message = messageFor(undefined);
    expect(message).toBe('Something went wrong. Please try again.');
  });

  it('should return fallback for empty object', () => {
    const message = messageFor({});
    expect(message).toBe('Something went wrong. Please try again.');
  });

  /**
   * Test 6: The server `message` text is never shown
   * Pass a message like "SECRET-DEV-TEXT" and assert it is not in the result.
   */
  it('should never show the server message text', () => {
    const error = new HttpErrorResponse({
      status: 401,
      error: { errorCode: 'AUTH-401', message: 'SECRET-DEV-TEXT-PASSWORD-WRONG' }
    });
    const message = messageFor(error);

    expect(message).not.toContain('SECRET-DEV-TEXT-PASSWORD-WRONG');
    expect(message).toContain('Sign-in was refused');
  });

  /**
   * Test 7: The prototype case - errorCode "constructor" returns the fallback
   * Ensures we don't accidentally use inherited properties.
   */
  it('should return fallback for prototype pollution attempt', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: { errorCode: 'constructor', message: 'Prototype pollution' }
    });
    const message = messageFor(error);

    expect(message).toBe('Something went wrong. Please try again.');
    expect(message).not.toContain('constructor');
  });

  /**
   * Integration: Ensure correct AUTH-401 message wording (used by e2e tests)
   */
  it('should return the AUTH-401 sentence for sign-in failures', () => {
    const error = new HttpErrorResponse({
      status: 401,
      error: { errorCode: 'AUTH-401', message: 'Invalid credentials' }
    });
    const message = messageFor(error);

    expect(message).toContain('Sign-in was refused');
    expect(message).toContain('or your session has expired');
  });
});
