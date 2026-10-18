import { describe, expect, it } from 'vitest';

import { calendarYear, financialYear } from './statements.service';

describe('statement ranges', () => {
  it('the Indian financial year runs 1 April to 31 March, and a date before April belongs to the year before', () => {
    expect(financialYear(new Date(2026, 3, 5))).toEqual({ from: '2026-04-01', to: '2027-03-31' });
    expect(financialYear(new Date(2027, 0, 10))).toEqual({ from: '2026-04-01', to: '2027-03-31' });
    expect(financialYear(new Date(2026, 2, 31))).toEqual({ from: '2025-04-01', to: '2026-03-31' });
  });

  it('the calendar year is January to December', () => {
    expect(calendarYear(new Date(2026, 9, 5))).toEqual({ from: '2026-01-01', to: '2026-12-31' });
  });
});
