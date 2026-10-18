import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SessionStore } from '../auth/session.store';
import { LeavePageConfirmService } from './leave-page-confirm.service';
import { HasUnsavedChanges, UNSAVED_CHANGES_MESSAGE, unsavedChangesGuard } from './unsaved-changes.guard';

describe('unsavedChangesGuard', () => {
  let sessionStore: SessionStore;
  let leavePrompt: LeavePageConfirmService;
  let requestLeaveSpy: ReturnType<typeof vi.spyOn>;

  const screen = (unsaved: boolean): HasUnsavedChanges => ({ hasUnsavedChanges: () => unsaved });
  const leave = (unsaved: boolean) =>
    TestBed.runInInjectionContext(() =>
      unsavedChangesGuard(screen(unsaved), {} as ActivatedRouteSnapshot, {} as RouterStateSnapshot, {} as RouterStateSnapshot)
    );

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [SessionStore, LeavePageConfirmService] });
    sessionStore = TestBed.inject(SessionStore);
    leavePrompt = TestBed.inject(LeavePageConfirmService);
    sessionStore.set({ accessToken: 'access-token' });
    requestLeaveSpy = vi.spyOn(leavePrompt, 'requestLeave');
  });

  afterEach(() => {
    requestLeaveSpy.mockRestore();
  });

  it('lets the user leave without asking when there is nothing to lose', () => {
    expect(leave(false)).toBe(true);
    expect(requestLeaveSpy).not.toHaveBeenCalled();
  });

  it('asks first when there is unsent input, and says what will be lost', async () => {
    requestLeaveSpy.mockResolvedValue(true);

    await leave(true);

    expect(requestLeaveSpy).toHaveBeenCalledTimes(1);
    expect(requestLeaveSpy).toHaveBeenCalledWith(UNSAVED_CHANGES_MESSAGE);
    expect(UNSAVED_CHANGES_MESSAGE).toContain('will be lost');
  });

  it('leaves when the user confirms', async () => {
    requestLeaveSpy.mockResolvedValue(true);

    await expect(leave(true)).resolves.toBe(true);
  });

  it('stays when the user cancels', async () => {
    requestLeaveSpy.mockResolvedValue(false);

    await expect(leave(true)).resolves.toBe(false);
  });

  it('never blocks once the session is over, so an expired session or a sign-out cannot get stuck behind a prompt', () => {
    sessionStore.clear();

    expect(leave(true)).toBe(true);
    expect(requestLeaveSpy).not.toHaveBeenCalled();
  });
});
