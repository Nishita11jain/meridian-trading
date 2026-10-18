import { Injectable, computed, signal } from '@angular/core';

const STORAGE_KEY = 'meridian-motion';
const QUERY = '(prefers-reduced-motion: reduce)';

/** 'on' / 'off' is the user's own choice; null follows the operating system. */
type Override = 'on' | 'off' | null;

function readOverride(): Override {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === 'on' || stored === 'off' ? stored : null;
  } catch {
    return null;
  }
}

function systemReduces(): boolean {
  return typeof matchMedia === 'function' && matchMedia(QUERY).matches;
}

/**
 * Whether the app should animate. It follows the operating system's "reduce motion" setting by
 * default, and the pause/play button on the landing and sign-in pages lets a person override it in
 * either direction (remote and server desktops often have animations switched off system-wide, so
 * without an override the page would look static to everyone who opens it there).
 *
 * The effective state is mirrored onto `<html>` as the `reduce-motion` class. All motion CSS keys off
 * that class rather than the media query, so the override reaches the stylesheets and the canvas
 * scene through one switch. The choice is stored in localStorage and is never sent anywhere.
 */
@Injectable({ providedIn: 'root' })
export class MotionService {
  private readonly override = signal<Override>(readOverride());
  private readonly system = signal(systemReduces());

  /** True when animations should be skipped. */
  readonly reduced = computed(() => {
    const choice = this.override();
    return choice === null ? this.system() : choice === 'off';
  });

  constructor() {
    if (typeof matchMedia === 'function') {
      matchMedia(QUERY).addEventListener?.('change', (event) => {
        this.system.set(event.matches);
        this.apply();
      });
    }
    this.apply();
  }

  /** Flips whatever is in effect now, and remembers it. */
  toggle(): void {
    const next: Override = this.reduced() ? 'on' : 'off';
    this.override.set(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Storage may be unavailable (private browsing); the choice just won't persist.
    }
    this.apply();
  }

  private apply(): void {
    document.documentElement.classList.toggle('reduce-motion', this.reduced());
  }
}
