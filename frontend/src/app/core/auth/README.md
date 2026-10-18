# core/auth

The session: who is signed in and how the app proves it.

- `session.store.ts`: `SessionStore` holds the access and refresh tokens as signals, mirrors them
  into `sessionStorage` (so a reload in the same tab keeps the session and closing the tab ends it),
  and exposes `isAuthenticated`. It has no HTTP dependency, so the interceptor and the guard can
  both use it without a circular dependency.
- `auth.service.ts`: `AuthService` wraps the generated Auth client: `login`, `register`, `refresh`,
  `getProfile` (`/auth/me`, the source of the account the session may trade) and `logout`.

Not the sign-in screen itself: that lives in `features/sign-in`.
