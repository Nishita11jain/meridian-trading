import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { SessionStore } from '../auth/session.store';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  let sessionStore: SessionStore;
  let mockRouter: { createUrlTree: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    sessionStorage.clear();

    mockRouter = {
      createUrlTree: vi.fn().mockReturnValue('redirect-tree')
    };

    TestBed.configureTestingModule({
      providers: [
        SessionStore,
        { provide: Router, useValue: mockRouter }
      ]
    });

    sessionStore = TestBed.inject(SessionStore);
  });

  it('the guard blocks unauthenticated navigation and redirects', () => {
    const state = { url: '/dashboard?tab=orders' } as RouterStateSnapshot;

    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, state)
    );

    expect(result).toBe('redirect-tree');
    expect(mockRouter.createUrlTree).toHaveBeenCalledWith(['/sign-in'], {
      queryParams: { returnUrl: '/dashboard?tab=orders' }
    });
  });

  it('the guard allows authenticated navigation', () => {
    sessionStore.set({ accessToken: 'access-token' });

    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/dashboard' } as RouterStateSnapshot)
    );

    expect(result).toBe(true);
    expect(mockRouter.createUrlTree).not.toHaveBeenCalled();
  });

  it('the attempted URL is handed to sign-in unchanged, where the return address is validated before it is followed', () => {
    const state = { url: 'https://evil.example/phish' } as RouterStateSnapshot;

    TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, state)
    );

    expect(mockRouter.createUrlTree).toHaveBeenCalledWith(['/sign-in'], {
      queryParams: { returnUrl: 'https://evil.example/phish' }
    });
  });
});
