import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject } from '@angular/core';
import { Router } from '@angular/router';

import { SessionStore } from '../auth/session.store';

/** How long a signed-in user may do nothing before the session is ended. */
export const IDLE_LIMIT_MS = 15 * 60 * 1000;
/** How often the clock is checked. A clock check (not one long timer) also works after a laptop sleeps. */
export const IDLE_CHECK_MS = 15 * 1000;

const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart', 'scroll'] as const;

/**
 * Signs the user out after IDLE_LIMIT_MS without a click, key press, scroll or touch, so a screen left
 * open on a shared computer does not stay signed in. It only runs while a session exists, and ends it
 * the same way an expired session does (so the unsaved-order prompt cannot block it).
 */
@Injectable({ providedIn: 'root' })
export class IdleSignOutService {
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);

  private lastActivity = Date.now();
  private checkHandle: ReturnType<typeof setInterval> | null = null;
  private readonly onActivity = () => {
    this.lastActivity = Date.now();
  };

  constructor() {
    effect(() => {
      if (this.session.isAuthenticated()) {
        this.start();
      } else {
        this.stop();
      }
    });
  }

  private start(): void {
    if (this.checkHandle !== null) {
      return;
    }
    this.lastActivity = Date.now();
    for (const name of ACTIVITY_EVENTS) {
      this.document.addEventListener(name, this.onActivity, { passive: true, capture: true });
    }
    this.checkHandle = setInterval(() => this.check(), IDLE_CHECK_MS);
  }

  private stop(): void {
    if (this.checkHandle === null) {
      return;
    }
    clearInterval(this.checkHandle);
    this.checkHandle = null;
    for (const name of ACTIVITY_EVENTS) {
      this.document.removeEventListener(name, this.onActivity, { capture: true });
    }
  }

  private check(): void {
    if (Date.now() - this.lastActivity < IDLE_LIMIT_MS) {
      return;
    }
    this.session.clear();
    void this.router.navigate(['/sign-in'], { queryParams: { reason: 'idle' } });
  }
}
