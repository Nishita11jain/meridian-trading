// SEC2-692: Error message catalog for all error codes from auth and trade APIs
// Types the error codes for compile-time completeness guarantee
// All codes derived from generated ErrorResponse enums in both clients

import { ErrorResponse as TradeErrorResponse } from '../../clients/trade-client/model/errorResponse';
import { ErrorResponse as AuthErrorResponse } from '../../clients/auth-client/model/errorResponse';

export type ErrorCode = TradeErrorResponse.ErrorCodeEnum | AuthErrorResponse.ErrorCodeEnum;

/**
 * Compile-time complete mapping of all error codes to user-facing messages.
 * Every value the template can display must be a signal; every branch of error
 * handling must have a message. This Record ensures no codes are missed.
 * 
 * Messages are trader-facing, action-oriented, and guide users toward resolution.
 */
const MESSAGES: Record<ErrorCode, string> = {
  'AUTH-401': 'Sign-in was refused, or your session has expired. Check your username and password, or sign in again.',
  'AUTH-409': 'That username is already taken. Choose another one.',
  'ACC-404': 'That account could not be found. Contact support.',
  'ACC-403': 'This account is not active, or is not the account this sign-in may trade. Contact support.',
  'INS-404': 'That instrument cannot be traded. Check the symbol and try again.',
  'ORD-400': 'There is not enough cash for this order. Reduce the quantity or price, or add funds.',
  'ORD-409': 'You do not hold enough to sell that quantity, or this order has already been placed. Check your holdings and the blotter before trying again.',
  'VAL-422': 'One of the fields is not acceptable. Check what you entered and try again.',
  'MKT-409': 'That market is closed now, so the order was not placed. Try again when it opens.',
};

/**
 * Extract error code from HttpErrorResponse or ErrorResponse-shaped object.
 * HttpErrorResponse wraps the API envelope in the `error` property.
 * Also handles bare {errorCode} objects (for testing).
 * 
 * Branches on errorCode if present (preferred).
 * Falls back to HTTP status 0 (network error) or unknown.
 * Never shows the server `message` field.
 * 
 * @param error Unknown error object (likely HttpErrorResponse, ErrorResponse, or Error)
 * @returns User-facing error message
 */
export function messageFor(error: unknown): string {
  // Handle HttpErrorResponse or error objects
  if (error && typeof error === 'object') {
    const err = error as Record<string, unknown>;

    // Read errorCode from err.error (HttpErrorResponse) or err directly (bare envelope)
    let code: string | undefined;
    if (err['error'] && typeof err['error'] === 'object') {
      const errorObj = err['error'] as Record<string, unknown>;
      code = typeof errorObj['errorCode'] === 'string' ? errorObj['errorCode'] : undefined;
    } else if (typeof err['errorCode'] === 'string') {
      code = err['errorCode'];
    }

    // If we found a code and it's in MESSAGES, return the mapped message
    if (code && Object.hasOwn(MESSAGES, code)) {
      return MESSAGES[code as ErrorCode];
    }

    // The Auth service throttles repeated sign-in attempts with a 429 (code RATE-429). That code is in
    // neither contract's catalogue, so it is recognised by its status rather than added to the map.
    if ('status' in err && err['status'] === 429) {
      return 'Too many attempts. Wait a few minutes before trying again.';
    }

    // Check for HTTP status 0 (network error, likely CORS or service unreachable)
    if ('status' in err && err['status'] === 0) {
      return 'We could not reach the service. It may be down, or your browser may be blocking the request. Try again shortly.';
    }
  }

  // Fallback for unknown errors (including unknown codes, non-JSON bodies, strings, null, undefined)
  return 'Something went wrong. Please try again.';
}
