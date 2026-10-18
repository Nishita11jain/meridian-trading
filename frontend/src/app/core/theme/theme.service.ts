import { Injectable, inject, signal } from '@angular/core';

import { MotionService } from '../motion/motion.service';

const STORAGE_KEY = 'lilac-theme';

/**
 * Dark is this platform's default: trading screens are watched for hours, often in dim rooms, and the
 * near-monochrome dark surfaces are what let the reserved red/green of a price move read instantly.
 * Light is the toggle. A stored choice always wins over the default.
 */
function prefersDark(): boolean {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'light') {
      return false;
    }
    if (stored === 'dark') {
      return true;
    }
  } catch {
    // Storage unavailable (private browsing): fall through to the default.
  }
  return true;
}

/**
 * Tracks light/dark theme as a signal and reflects it onto
 * `document.documentElement` as a `dark` class, which every component's
 * CSS custom properties key off. Persisted to localStorage; not shared
 * with anyone else or read back by the server.
 */
@Injectable({
  providedIn: 'root'
})
export class ThemeService {
  private readonly motion = inject(MotionService);
  readonly dark = signal(prefersDark());

  constructor() {
    this.apply(this.dark());
  }

  toggle(): void {
    this.set(!this.dark());
  }

  set(dark: boolean): void {
    this.dark.set(dark);
    // Apply the theme as it is when the update runs, not as it was when it was queued: two quick changes
    // start two transitions, the browser skips the first, and its update can run after the second's.
    this.crossFade(() => this.apply(this.dark()));
    try {
      localStorage.setItem(STORAGE_KEY, dark ? 'dark' : 'light');
    } catch {
      // Storage may be unavailable (private browsing); theme just won't persist.
    }
  }

  /**
   * Cross-fades the whole page between themes where the browser supports View Transitions, so
   * every surface changes together instead of each element at its own speed. Without the API, or
   * when the user prefers reduced motion, the theme switches instantly.
   */
  private crossFade(update: () => void): void {
    const doc = document as Document & { startViewTransition?: (cb: () => void) => unknown };
    if (typeof doc.startViewTransition !== 'function' || this.motion.reduced()) {
      update();
      return;
    }
    doc.startViewTransition(update);
  }

  private apply(dark: boolean): void {
    document.documentElement.classList.toggle('dark', dark);
  }
}
