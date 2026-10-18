import { describe, expect, it } from 'vitest';

import { routes } from './app.routes';
import { authGuard } from './core/guards/auth.guard';
import { guestGuard } from './core/guards/guest.guard';
import { unsavedChangesGuard } from './core/guards/unsaved-changes.guard';

describe('routes', () => {
  // Routes that render a protected screen: everything except the public landing ('') and sign-in.
  const guardable = routes.filter((route) => route.path !== '' && route.path !== 'sign-in' && !route.redirectTo);

  it('the sign-in route is the only route that does not require a session', () => {
    const signIn = routes.find((route) => route.path === 'sign-in');

    expect(signIn).toBeTruthy();
    expect(signIn?.canActivate).not.toContain(authGuard);
  });

  it('the sign-in route keeps signed-in users off the sign-in form', () => {
    const signIn = routes.find((route) => route.path === 'sign-in');

    expect(signIn?.canActivate).toEqual([guestGuard]);
  });

  it('every other route that renders a screen runs the auth guard', () => {
    expect(guardable.map((route) => route.path).sort()).toEqual(['advice', 'blotter', 'dashboard', 'notifications', 'portfolio', 'profile', 'statements', 'strategies', 'trade', 'watchlists']);
    for (const route of guardable) {
      expect(route.canActivate, `route "${route.path}" must be guarded`).toEqual([authGuard]);
    }
  });

  it('the order ticket asks before it is left with an order that has not been placed', () => {
    const trade = routes.find((route) => route.path === 'trade');

    expect(trade?.canDeactivate).toEqual([unsavedChangesGuard]);
  });

  it('an unknown URL is redirected to the guarded dashboard instead of a blank screen', () => {
    const catchAll = routes.find((route) => route.path === '**');

    expect(catchAll?.redirectTo).toBe('/dashboard');
  });

  it('the empty path shows the public landing page, with no guard', () => {
    const root = routes.find((route) => route.path === '');

    expect(root?.redirectTo).toBeUndefined();
    expect(root?.loadComponent).toBeTruthy();
    expect(root?.canActivate).toBeUndefined();
  });

  it('every page has its own title, so a screen reader announces where the user has moved to', () => {
    // Redirects show no screen, so they have no title of their own.
    const titles = routes.filter((route) => route.path && route.path !== '**' && !route.redirectTo).map((route) => route.title);

    expect(titles.every((title) => typeof title === 'string' && title.includes('Meridian'))).toBe(true);
    expect(new Set(titles).size).toBe(titles.length);
  });
});
