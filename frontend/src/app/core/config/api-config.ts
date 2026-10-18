// API base URLs used by the generated clients, and the allow list the bearer-token
// interceptor checks outgoing requests against.
//
// Development: both bases are '' (same origin). `ng serve` uses proxy.conf.js to forward
// /auth to the Auth service (localhost:3000) and /api to the Trade REST API (localhost:8081),
// so the browser never makes a cross-origin call and no CORS rule is needed.
//
// Production: there is no dev proxy. Either serve /auth and /api from the same origin behind a
// reverse proxy (keep the bases ''), or set absolute URLs here; an absolute URL is then a
// cross-origin call and the service must allow this app's origin in its CORS configuration.
// Only origins and paths belong in this file. Never a key, token or secret: it ships to the browser.

export const AUTH_API_BASE = '';
export const TRADE_API_BASE = '';

/** Auth routes that take no Authorization header (`security: []` in auth-api.yaml). */
export const UNAUTHENTICATED_AUTH_PATHS = [
	'/auth/login',
	'/auth/register',
	'/auth/refresh'
] as const;

/**
 * The only paths on an allowed origin that receive the bearer token: every Trade REST API
 * route, and the one protected Auth route. Anything else, even on the same origin, gets none.
 */
export const PLATFORM_API_PATH_PREFIXES = ['/api/'] as const;
export const PLATFORM_API_EXACT_PATHS = ['/auth/me'] as const;
