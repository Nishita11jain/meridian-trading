import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('dark');
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    document.documentElement.classList.remove('dark');
  });

  it('starts dark when nothing is stored, because dark is this platform default', () => {
    const theme = TestBed.inject(ThemeService);

    expect(theme.dark()).toBe(true);
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });

  it('toggling switches to light, unmarks the page and remembers the choice', () => {
    const theme = TestBed.inject(ThemeService);

    theme.toggle();

    expect(theme.dark()).toBe(false);
    expect(document.documentElement.classList.contains('dark')).toBe(false);
    expect(localStorage.getItem('lilac-theme')).toBe('light');
  });

  it('restores a stored dark choice when the app starts', () => {
    localStorage.setItem('lilac-theme', 'dark');

    const theme = TestBed.inject(ThemeService);

    expect(theme.dark()).toBe(true);
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });

  it('restores a stored light choice, which wins over the dark default', () => {
    localStorage.setItem('lilac-theme', 'light');

    const theme = TestBed.inject(ThemeService);

    expect(theme.dark()).toBe(false);
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('still switches theme when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    const theme = TestBed.inject(ThemeService);

    expect(() => theme.toggle()).not.toThrow();
    expect(theme.dark()).toBe(false);
  });

  it('two quick changes during cross-fades end on the latest theme, whatever order the browser runs them in', () => {
    // A browser with view transitions, where the first (skipped) transition's update runs last.
    const queued: Array<() => void> = [];
    Object.defineProperty(document, 'startViewTransition', {
      configurable: true,
      value: (update: () => void) => { queued.push(update); return {}; }
    });
    try {
      const theme = TestBed.inject(ThemeService);

      theme.set(false);
      theme.set(true);
      queued.reverse().forEach((update) => update());

      expect(document.documentElement.classList.contains('dark')).toBe(true);
    } finally {
      Reflect.deleteProperty(document, 'startViewTransition');
    }
  });
});
