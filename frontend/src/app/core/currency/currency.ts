/**
 * One way to write money everywhere in the app: the currency symbol, then the amount, grouped the same way
 * (for example ₹1,234.56 and $1,234.56). The platform only trades in rupees and dollars; any other code is
 * written out in full so nothing is ever shown without its currency.
 */
const SYMBOLS: Record<string, string> = { INR: '₹', USD: '$' };

const formatters = new Map<string, Intl.NumberFormat>();

function numberFormat(minDigits: number, maxDigits: number): Intl.NumberFormat {
  const key = `${minDigits}-${maxDigits}`;
  let format = formatters.get(key);
  if (!format) {
    format = new Intl.NumberFormat('en-US', { minimumFractionDigits: minDigits, maximumFractionDigits: maxDigits });
    formatters.set(key, format);
  }
  return format;
}

/** The symbol for a currency code, or the code and a space for one we have no symbol for. */
export function currencySymbol(code: string | null | undefined): string {
  if (!code) {
    return '';
  }
  return SYMBOLS[code] ?? `${code} `;
}

/** The currency's symbol and the amount: ₹1,234.56. Negative amounts put the minus before the symbol. */
export function formatMoney(
  amount: number, currency: string | null | undefined, digits = 2, signed = false, maxDigits = digits
): string {
  const magnitude = numberFormat(digits, Math.max(digits, maxDigits)).format(Math.abs(amount));
  const sign = amount < 0 ? '-' : signed && amount > 0 ? '+' : '';
  return `${sign}${currencySymbol(currency)}${magnitude}`;
}
