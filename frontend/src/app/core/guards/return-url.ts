/** Where a user lands when there is no usable return address. */
export const DEFAULT_RETURN_PATH = '/dashboard';

/**
 * Turns the `returnUrl` query parameter into a path on this origin, or the default.
 *
 * The parameter is controlled by whoever built the link, so following it unchecked is an open
 * redirect. Only a path on this origin is accepted; another host, `//host`, `javascript:`,
 * `data:` and user-information tricks all fall back to the dashboard. A path under /sign-in is
 * refused too, so a return address can never bounce a signed-in user back to the sign-in page.
 */
export function safeReturnPath(
  candidate: string | null | undefined,
  origin: string | undefined = globalThis.location?.origin
): string {
  if (!candidate) {
    return DEFAULT_RETURN_PATH;
  }

  try {
    const parsed = new URL(candidate, origin);
    if (origin && parsed.origin !== origin) {
      return DEFAULT_RETURN_PATH;
    }

    if (!parsed.pathname.startsWith('/') || parsed.pathname.startsWith('/sign-in')) {
      return DEFAULT_RETURN_PATH;
    }

    return `${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return DEFAULT_RETURN_PATH;
  }
}
