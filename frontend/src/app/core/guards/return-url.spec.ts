import { describe, expect, it } from 'vitest';

import { DEFAULT_RETURN_PATH, safeReturnPath } from './return-url';

const ORIGIN = 'http://localhost:4200';

describe('safeReturnPath', () => {
  it.each([
    ['a plain path', '/trade', '/trade'],
    ['a path with a query string and a fragment', '/blotter?tab=1#top', '/blotter?tab=1#top'],
    ['an absolute URL on this origin, as a path only', `${ORIGIN}/blotter?tab=1`, '/blotter?tab=1']
  ])('follows %s', (_label, candidate, expected) => {
    expect(safeReturnPath(candidate, ORIGIN)).toBe(expected);
  });

  it.each([
    ['a protocol-relative URL to another host', '//evil.example/phish'],
    ['a javascript: URL', 'javascript:alert(1)'],
    ['a backslash that browsers read as a slash', '/\\evil.example'],
    ['a data: URL', 'data:text/html,<script>alert(1)</script>'],
    ['another host that starts with our host name', 'https://localhost.evil.example/x'],
    ['another host hidden behind user information', 'https://localhost@evil.example/x'],
    ['an https URL on another host', 'https://evil.example/phish'],
    ['the same host on another port', 'http://localhost:9999/x']
  ])('refuses %s', (_label, candidate) => {
    expect(safeReturnPath(candidate, ORIGIN)).toBe(DEFAULT_RETURN_PATH);
  });

  it.each([null, undefined, ''])('uses the default for %p', (candidate) => {
    expect(safeReturnPath(candidate, ORIGIN)).toBe(DEFAULT_RETURN_PATH);
  });

  it.each(['/sign-in', '/sign-in?returnUrl=%2Fblotter', '/sign-in/anything'])(
    'never returns the sign-in page itself (%s), so a signed-in user cannot loop',
    (candidate) => {
      expect(safeReturnPath(candidate, ORIGIN)).toBe(DEFAULT_RETURN_PATH);
    }
  );
});
