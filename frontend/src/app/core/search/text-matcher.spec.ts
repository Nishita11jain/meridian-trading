import { describe, expect, it } from 'vitest';

import { MAX_PATTERN_LENGTH, createMatcher } from './text-matcher';

describe('createMatcher', () => {
  it('matches everything for an empty or blank query, in either mode', () => {
    for (const regex of [false, true]) {
      const m = createMatcher('   ', regex);
      expect(m.empty).toBe(true);
      expect(m.error).toBeNull();
      expect(m.test('anything')).toBe(true);
    }
  });

  it('plain mode is a case-insensitive "contains" across the given fields', () => {
    const m = createMatcher('tcs', false);
    expect(m.test('TCS.NS')).toBe(true);
    expect(m.test('INFY.NS', 'ORD-tcs-1')).toBe(true);
    expect(m.test('INFY.NS')).toBe(false);
  });

  it('plain mode treats regex characters literally', () => {
    const m = createMatcher('.ns', false);
    expect(m.test('TCS.NS')).toBe(true);
    expect(m.test('TCSXNS')).toBe(false);
  });

  it('regex mode supports alternation and anchors, case-insensitively by default', () => {
    const m = createMatcher('^(tcs|infy)', true);
    expect(m.test('TCS.NS')).toBe(true);
    expect(m.test('INFY.NS')).toBe(true);
    expect(m.test('HDFCBANK.NS')).toBe(false);
  });

  it('regex mode honours /pattern/flags, including case-sensitive search', () => {
    const sensitive = createMatcher('/^tcs/', true);
    expect(sensitive.test('TCS.NS')).toBe(false);
    expect(sensitive.test('tcs.ns')).toBe(true);
  });

  it('a global flag does not make repeated tests stateful', () => {
    const m = createMatcher('/ns/gi', true);
    expect(m.test('TCS.NS')).toBe(true);
    expect(m.test('TCS.NS')).toBe(true);
  });

  it('an invalid pattern reports why and matches everything instead of blanking the list', () => {
    const m = createMatcher('(tcs', true);
    expect(m.error).toMatch(/Not a valid pattern/);
    expect(m.test('INFY.NS')).toBe(true);
  });

  it('refuses over-long patterns', () => {
    const m = createMatcher('a'.repeat(MAX_PATTERN_LENGTH + 1), true);
    expect(m.error).toContain(String(MAX_PATTERN_LENGTH));
    expect(m.test('x')).toBe(true);
  });

  it('ignores null and undefined fields and matches numbers as text', () => {
    const m = createMatcher('25', false);
    expect(m.test(null, undefined, 1250)).toBe(true);
    expect(m.test(null, undefined)).toBe(false);
  });
});
