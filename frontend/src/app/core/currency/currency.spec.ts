import { currencySymbol, formatMoney } from './currency';

describe('currency', () => {
  it('uses ₹ and $ for the two currencies the platform trades in', () => {
    expect(currencySymbol('INR')).toBe('₹');
    expect(currencySymbol('USD')).toBe('$');
  });

  it('writes any other currency out in full, so nothing is shown without one', () => {
    expect(currencySymbol('GBP')).toBe('GBP ');
    expect(formatMoney(5, 'GBP')).toBe('GBP 5.00');
    expect(currencySymbol(null)).toBe('');
  });

  it('puts the symbol first and groups thousands the same way everywhere', () => {
    expect(formatMoney(1234.5, 'INR')).toBe('₹1,234.50');
    expect(formatMoney(255476.756, 'INR')).toBe('₹255,476.76');
    expect(formatMoney(14.8, 'USD')).toBe('$14.80');
  });

  it('puts a minus before the symbol and a plus only when asked', () => {
    expect(formatMoney(-12.5, 'USD')).toBe('-$12.50');
    expect(formatMoney(12.5, 'USD', 2, true)).toBe('+$12.50');
    expect(formatMoney(0, 'USD', 2, true)).toBe('$0.00');
  });

  it('shows up to four decimals for a price when asked, but never fewer than two', () => {
    expect(formatMoney(10, 'USD', 2, false, 4)).toBe('$10.00');
    expect(formatMoney(0.12345, 'USD', 2, false, 4)).toBe('$0.1235');
  });
});
