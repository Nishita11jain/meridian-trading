import { signal } from '@angular/core';
import { formatMoney } from '../currency';

/**
 * Stands in for DisplayCurrencyService in component specs: amounts are shown in their own currency, with the same
 * symbols and grouping as the real service, and nothing is fetched. The real conversion has its own spec.
 */
export function displayCurrencyStub(preferred = 'INR') {
  return {
    preferred: signal(preferred),
    ensureLoaded: () => undefined,
    setPreferred: () => undefined,
    reset: () => undefined,
    convert: (amount: number, from: string) => ({ amount, currency: from }),
    show: (amount: number | string | null | undefined, from: string, digits = 2) =>
      amount === null || amount === undefined || amount === '' ? '—' : formatMoney(Number(amount), from, digits),
    showSigned: (amount: number | string | null | undefined, from: string, digits = 2) =>
      amount === null || amount === undefined || amount === '' ? '—' : formatMoney(Number(amount), from, digits, true)
  };
}
