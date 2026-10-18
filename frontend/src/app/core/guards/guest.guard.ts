import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';

import { SessionStore } from '../auth/session.store';
import { safeReturnPath } from './return-url';

/**
 * For the sign-in route only: a signed-in user has no use for the sign-in form, so they are sent on
 * (to the address in `returnUrl` if it is a path on this origin, otherwise the dashboard) instead
 * of being shown a login form while already logged in. A signed-out visitor goes straight through.
 *
 * A usability control, like authGuard: it hides a screen, it does not protect anything.
 * The session is cleared before the interceptor and sign-out navigate here, so an expired or
 * ended session is never mistaken for a signed-in one.
 */
export const guestGuard: CanActivateFn = (route) => {
  if (!inject(SessionStore).isAuthenticated()) {
    return true;
  }

  return inject(Router).parseUrl(safeReturnPath(route.queryParamMap.get('returnUrl')));
};
