import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SessionStore } from '../auth/session.store';
import { IDLE_CHECK_MS, IDLE_LIMIT_MS, IdleSignOutService } from './idle-sign-out.service';

describe('IdleSignOutService', () => {
  let session: SessionStore;
  let router: { navigate: ReturnType<typeof vi.fn> };

  function start() {
    TestBed.inject(IdleSignOutService);
    TestBed.tick();
  }

  beforeEach(() => {
    vi.useFakeTimers();
    sessionStorage.clear();
    router = { navigate: vi.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({ providers: [{ provide: Router, useValue: router }] });
    session = TestBed.inject(SessionStore);
  });

  afterEach(() => {
    vi.useRealTimers();
    sessionStorage.clear();
  });

  it('signs a user out after the idle limit and says why', () => {
    session.set({ accessToken: 'a', refreshToken: 'r' });
    start();

    vi.advanceTimersByTime(IDLE_LIMIT_MS + IDLE_CHECK_MS);

    expect(session.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/sign-in'], { queryParams: { reason: 'idle' } });
  });

  it('does not sign out before the limit', () => {
    session.set({ accessToken: 'a', refreshToken: 'r' });
    start();

    vi.advanceTimersByTime(IDLE_LIMIT_MS - IDLE_CHECK_MS * 2);

    expect(session.isAuthenticated()).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('activity (a key press, a click) restarts the clock', () => {
    session.set({ accessToken: 'a', refreshToken: 'r' });
    start();

    vi.advanceTimersByTime(IDLE_LIMIT_MS - IDLE_CHECK_MS * 2);
    document.dispatchEvent(new KeyboardEvent('keydown'));
    vi.advanceTimersByTime(IDLE_LIMIT_MS - IDLE_CHECK_MS * 2);

    expect(session.isAuthenticated()).toBe(true);

    vi.advanceTimersByTime(IDLE_CHECK_MS * 4);
    expect(session.isAuthenticated()).toBe(false);
  });

  it('does nothing while nobody is signed in', () => {
    start();

    vi.advanceTimersByTime(IDLE_LIMIT_MS * 3);

    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('stops watching once the session ends another way (sign-out), so it cannot redirect later', () => {
    session.set({ accessToken: 'a', refreshToken: 'r' });
    start();
    session.clear();
    TestBed.tick();

    vi.advanceTimersByTime(IDLE_LIMIT_MS * 3);

    expect(router.navigate).not.toHaveBeenCalled();
  });
});
