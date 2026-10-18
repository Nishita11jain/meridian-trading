/**
 * One search rule for every search box in the app, so they all behave the same:
 *
 *  - Plain mode (the default): case-insensitive "contains". `tcs` finds TCS.NS.
 *  - Regex mode (the `.*` toggle): the text is a JavaScript regular expression, case-insensitive unless the
 *    pattern is written as `/.../flags` with its own flags. `^(TCS|INFY)` finds both; `\.NS$` finds NSE symbols.
 *
 * An empty query matches everything. A regex that does not compile yet (the user is still typing it) also
 * matches everything and reports the problem in `error`, so a half-typed pattern never blanks the table.
 */
export interface TextMatcher {
  /** Why the pattern could not be used, in plain words; null when it is fine. */
  readonly error: string | null;
  /** True when the query is empty, so callers can skip filtering. */
  readonly empty: boolean;
  /** True when any of the given fields matches. Null and undefined fields are ignored. */
  test(...fields: ReadonlyArray<string | number | null | undefined>): boolean;
}

/** Longer patterns are refused: a search box never needs one, and it bounds the work done per keystroke. */
export const MAX_PATTERN_LENGTH = 200;

const MATCH_ALL: Omit<TextMatcher, 'error'> = { empty: true, test: () => true };

export function createMatcher(query: string, regex: boolean): TextMatcher {
  const text = (query ?? '').trim();
  if (!text) {
    return { ...MATCH_ALL, error: null };
  }
  if (!regex) {
    const needle = text.toLowerCase();
    return {
      error: null,
      empty: false,
      test: (...fields) => fields.some((f) => f != null && String(f).toLowerCase().includes(needle))
    };
  }
  if (text.length > MAX_PATTERN_LENGTH) {
    return { ...MATCH_ALL, error: `Patterns are limited to ${MAX_PATTERN_LENGTH} characters.` };
  }
  const compiled = compile(text);
  if (typeof compiled === 'string') {
    return { ...MATCH_ALL, error: compiled };
  }
  return {
    error: null,
    empty: false,
    test: (...fields) => fields.some((f) => {
      if (f == null) {
        return false;
      }
      // A global or sticky flag makes `test` stateful between calls; reset so every field starts at 0.
      compiled.lastIndex = 0;
      return compiled.test(String(f));
    })
  };
}

/** `/body/flags` keeps the user's flags; anything else is the body itself, matched case-insensitively. */
function compile(text: string): RegExp | string {
  const literal = /^\/(.+)\/([a-z]*)$/i.exec(text);
  const body = literal ? literal[1] : text;
  const flags = literal ? literal[2] : 'i';
  try {
    return new RegExp(body, flags);
  } catch (err) {
    const reason = err instanceof Error ? err.message.replace(/^Invalid regular expression: /, '') : '';
    return reason ? `Not a valid pattern: ${reason}` : 'Not a valid pattern.';
  }
}
