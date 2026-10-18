import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { SessionStore } from '../auth/session.store';
import { guestGuard } from './guest.guard';

describe('guestGuard', () => {
  let sessionStore: SessionStore;
  let mockRouter: { parseUrl: ReturnType<typeof vi.fn> };

  const routeWith = (params: Record<string, string> = {}) =>
    ({ queryParamMap: convertToParamMap(params) }) as unknown as ActivatedRouteSnapshot;

  const run = (params: Record<string, string> = {}) =>
    TestBed.runInInjectionContext(() => guestGuard(routeWith(params), {} as RouterStateSnapshot));

  beforeEach(() => {
    sessionStorage.clear();
    mockRouter = { parseUrl: vi.fn().mockReturnValue('redirect-tree') };

    TestBed.configureTestingModule({
      providers: [SessionStore, { provide: Router, useValue: mockRouter }]
    });

    sessionStore = TestBed.inject(SessionStore);
  });

  it('lets a signed-out visitor see the sign-in page', () => {
    expect(run()).toBe(true);
    expect(mockRouter.parseUrl).not.toHaveBeenCalled();
  });

  it('sends a signed-in user to the dashboard instead of the sign-in form', () => {
    sessionStore.set({ accessToken: 'access-token' });

    expect(run()).toBe('redirect-tree');
    expect(mockRouter.parseUrl).toHaveBeenCalledWith('/dashboard');
  });

  it('sends a signed-in user to the page in returnUrl when it is a path on this origin', () => {
    sessionStore.set({ accessToken: 'access-token' });

    run({ returnUrl: '/blotter?tab=1' });

    expect(mockRouter.parseUrl).toHaveBeenCalledWith('/blotter?tab=1');
  });

  it.each(['https://evil.example/phish', '//evil.example', 'javascript:alert(1)'])(
    'ignores an off-origin returnUrl (%s) for a signed-in user',
    (returnUrl) => {
      sessionStore.set({ accessToken: 'access-token' });

      run({ returnUrl });

      expect(mockRouter.parseUrl).toHaveBeenCalledWith('/dashboard');
    }
  );

  it('does not bounce a signed-in user back to the sign-in page', () => {
    sessionStore.set({ accessToken: 'access-token' });

    run({ returnUrl: '/sign-in' });

    expect(mockRouter.parseUrl).toHaveBeenCalledWith('/dashboard');
  });

  it('treats a session that was just cleared as signed out', () => {
    sessionStore.set({ accessToken: 'access-token' });
    sessionStore.clear();

    expect(run({ reason: 'expired' })).toBe(true);
  });
});
