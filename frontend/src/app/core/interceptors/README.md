# core/interceptors

`auth-header.interceptor.ts` is the single functional interceptor, registered once in `app.config.ts`
through `withInterceptors`, and the only place in the app that sets an `Authorization` header.

- It attaches `Bearer <token>` only when the origin is one we configured **and** the path is a
  platform API path (`/api/...` or `/auth/me`). It is an allow list, so a new third party gets no token.
- `/auth/login`, `/auth/register` and `/auth/refresh` never get a header.
- A `401` on a call that carried the token means the session is over: it clears the session and sends
  the user to sign-in with `returnUrl` and `reason=expired`, once however many calls failed together.
