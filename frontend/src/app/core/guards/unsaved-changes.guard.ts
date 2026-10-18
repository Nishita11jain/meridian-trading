import { CanDeactivateFn } from '@angular/router';
import { inject } from '@angular/core';

import { SessionStore } from '../auth/session.store';
import { LeavePageConfirmService } from './leave-page-confirm.service';

/** A screen that can say whether the user has entered something that has not been sent yet. */
export interface HasUnsavedChanges {
  hasUnsavedChanges(): boolean;
}

export const UNSAVED_CHANGES_MESSAGE =
  'You have an order that has not been placed. If you leave now, what you entered will be lost. Leave this page?';

/**
 * Asks before the user leaves a screen with unsent input (a link, or Back/Forward inside the app).
 * OK leaves, Cancel stays. Nothing is asked when:
 *  - there is nothing to lose (the screen reports no unsaved changes), or
 *  - the session is already over: the interceptor clears it before sending an expired session to
 *    sign-in, and sign-out clears it before navigating, so neither can be blocked by a prompt.
 * Closing the tab, reloading, or leaving the site is covered separately by the screen's own
 * `beforeunload` handler, because a router guard never sees those.
 */
export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = (component) => {
  if (!inject(SessionStore).isAuthenticated() || !component.hasUnsavedChanges()) {
    return true;
  }

  return inject(LeavePageConfirmService).requestLeave(UNSAVED_CHANGES_MESSAGE);
};
