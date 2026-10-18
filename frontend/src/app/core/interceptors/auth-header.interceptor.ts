import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, of, switchMap, throwError } from 'rxjs';

import { AuthService } from '../auth/auth.service';

import { SessionStore } from '../auth/session.store';
import {
  AUTH_API_BASE,
  PLATFORM_API_EXACT_PATHS,
  PLATFORM_API_PATH_PREFIXES,
  TRADE_API_BASE,
  UNAUTHENTICATED_AUTH_PATHS
} from '../config/api-config';

const ABSOLUTE_URL_PATTERN = /^https?:\/\//i;

function buildAllowedOrigins(): string[] {
  const origins = [
    resolveOrigin(AUTH_API_BASE),
    resolveOrigin(TRADE_API_BASE)
  ].filter(
    (origin): origin is string => Boolean(origin)
  );

  return Array.from(new Set(origins));
}

function resolveOrigin(baseUrl: string): string | null {
  if (!baseUrl || !ABSOLUTE_URL_PATTERN.test(baseUrl)) {
    return globalThis.location?.origin ?? null;
  }

  try {
    return new URL(baseUrl, globalThis.location?.origin).origin;
  } catch {
    return null;
  }
}

function isUnauthenticatedAuthRoute(url: URL): boolean {
  return UNAUTHENTICATED_AUTH_PATHS.some((path) => url.pathname === path || url.pathname === `${path}/`);
}

function isPlatformApiPath(url: URL): boolean {
  return (
    PLATFORM_API_PATH_PREFIXES.some((prefix) => url.pathname.startsWith(prefix)) ||
    PLATFORM_API_EXACT_PATHS.some((path) => url.pathname === path)
  );
}

/**
 * Decides by comparing the outgoing URL with the origins and paths we configured (an allow
 * list, so a new third party is refused by default): the origin must be one of ours and the
 * path must be a platform API path. The public auth routes never get a header.
 */
function shouldAttachToken(requestUrl: string): boolean {
  let parsedUrl: URL;

  try {
    parsedUrl = new URL(requestUrl, globalThis.location?.origin);
  } catch {
    return false;
  }

  if (!buildAllowedOrigins().includes(parsedUrl.origin)) {
    return false;
  }

  if (isUnauthenticatedAuthRoute(parsedUrl)) {
    return false;
  }

  return isPlatformApiPath(parsedUrl);
}

/**
 * The only place in the application that sets an Authorization header.
 *
 * It also handles an expired or revoked session: a 401 on a call that carried the token means
 * the token is no longer accepted, so the session is cleared and the user is sent to sign-in,
 * carrying the page they were on. Checking the session first means several calls failing at
 * once cause a single redirect.
 */
function endSession(sessionStore: SessionStore, router: Router): void {
  sessionStore.clear();
  void router.navigate(['/sign-in'], {
    queryParams: { returnUrl: router.url, reason: 'expired' }
  });
}

export const authHeaderInterceptor: HttpInterceptorFn = (req, next) => {
  if (!shouldAttachToken(req.url)) {
    return next(req);
  }

  const sessionStore = inject(SessionStore);
  const router = inject(Router);
  const token = sessionStore.accessToken();
  if (!token) {
    return next(req);
  }

  const authService = inject(AuthService);
  const send = (accessToken: string) => next(req.clone({ setHeaders: { Authorization: `Bearer ${accessToken}` } }));

  return send(token).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse && error.status === 401 && sessionStore.isAuthenticated())) {
        return throwError(() => error);
      }

      // Without a refresh token there is nothing to renew with: the session is over.
      if (!sessionStore.refreshToken()) {
        endSession(sessionStore, router);
        return throwError(() => error);
      }

      // The token was refused, so renew it once and repeat the call once. If another call has already
      // renewed it (the store holds a different token than the one this call sent), use that one:
      // refresh tokens are single-use, so a second refresh with the old token would fail.
      const renewed: Observable<string> =
        sessionStore.accessToken() !== token
          ? of(sessionStore.accessToken())
          : authService.refreshShared().pipe(switchMap((tokens) => of(tokens.accessToken)));

      return renewed.pipe(
        catchError((refreshError: unknown) => {
          // A refused refresh ends the session; a network or server failure does not, the user can retry.
          const status = refreshError instanceof HttpErrorResponse ? refreshError.status : 0;
          if (status === 0 || status >= 500) {
            return throwError(() => error);
          }
          endSession(sessionStore, router);
          return throwError(() => error);
        }),
        switchMap((accessToken) =>
          send(accessToken).pipe(
            catchError((retryError: unknown) => {
              if (retryError instanceof HttpErrorResponse && retryError.status === 401 && sessionStore.isAuthenticated()) {
                endSession(sessionStore, router);
              }
              return throwError(() => retryError);
            })
          )
        )
      );
    })
  );
};

export const authHeaderInterceptorInternals = {
  shouldAttachToken,
  buildAllowedOrigins,
  isUnauthenticatedAuthRoute,
  isPlatformApiPath,
  resolveOrigin
};
