import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';

import { SessionStore } from '../auth/session.store';

export const authGuard: CanActivateFn = (_route, state) => {
  const sessionStore = inject(SessionStore);
  if (sessionStore.isAuthenticated()) {
    return true;
  }

  const router = inject(Router);
  return router.createUrlTree(['/sign-in'], {
    queryParams: { returnUrl: state.url }
  });
};
