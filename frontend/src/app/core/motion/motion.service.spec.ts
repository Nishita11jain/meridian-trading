import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MotionService } from './motion.service';

const KEY = 'meridian-motion';

/** Pretends the operating system does (or does not) ask for reduced motion. */
function stubSystem(reduces: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('prefers-reduced-motion') && reduces,
    addEventListener: () => undefined,
    removeEventListener: () => undefined
  }));
}

describe('MotionService', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('reduce-motion');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
    document.documentElement.classList.remove('reduce-motion');
  });

  it('animates by default when the system does not ask for reduced motion', () => {
    stubSystem(false);

    const motion = TestBed.inject(MotionService);

    expect(motion.reduced()).toBe(false);
    expect(document.documentElement.classList.contains('reduce-motion')).toBe(false);
  });

  it('follows the system when it asks for reduced motion, and marks the page', () => {
    stubSystem(true);

    const motion = TestBed.inject(MotionService);

    expect(motion.reduced()).toBe(true);
    expect(document.documentElement.classList.contains('reduce-motion')).toBe(true);
  });

  it('lets the user turn animations back on over a system that asks to reduce them, and remembers it', () => {
    stubSystem(true);
    const motion = TestBed.inject(MotionService);

    motion.toggle();

    expect(motion.reduced()).toBe(false);
    expect(document.documentElement.classList.contains('reduce-motion')).toBe(false);
    expect(localStorage.getItem(KEY)).toBe('on');
  });

  it('lets the user pause animations on a system that allows them', () => {
    stubSystem(false);
    const motion = TestBed.inject(MotionService);

    motion.toggle();

    expect(motion.reduced()).toBe(true);
    expect(document.documentElement.classList.contains('reduce-motion')).toBe(true);
    expect(localStorage.getItem(KEY)).toBe('off');

    motion.toggle();

    expect(motion.reduced()).toBe(false);
    expect(localStorage.getItem(KEY)).toBe('on');
  });

  it('restores a stored choice, which wins over the system setting', () => {
    stubSystem(true);
    localStorage.setItem(KEY, 'on');

    const motion = TestBed.inject(MotionService);

    expect(motion.reduced()).toBe(false);
  });

  it('ignores a stored value it does not recognise', () => {
    stubSystem(true);
    localStorage.setItem(KEY, 'maybe');

    expect(TestBed.inject(MotionService).reduced()).toBe(true);
  });

  it('still switches when storage is unavailable', () => {
    stubSystem(false);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    const motion = TestBed.inject(MotionService);

    expect(() => motion.toggle()).not.toThrow();
    expect(motion.reduced()).toBe(true);
  });
});
