# core/errors

Maps every `errorCode` in both contracts to a sentence a trader can act on.

- `ErrorCode` is derived from the two generated `ErrorCodeEnum` types, so a code added to either
  contract is a compile error until it has a message.
- `messageFor(error)` reads `error.error.errorCode` (an `HttpErrorResponse` carries the API's
  `{errorCode, message}` body there) and never shows the server `message`.
- Status 0 (the browser got no response: service down, or a cross-origin rule blocks the request)
  and an unrecognised code each have their own fallback sentence, so a panel is never blank.

To add a code: add it to the contract, run `npm run generate`, then write its message; the
compiler and the spec will point at anything missing.
