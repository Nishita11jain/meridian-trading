import { Injectable, inject, signal } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { PreferencesService } from '../preferences/preferences.service';
import { WatchlistsService } from '../watchlists/watchlists.service';
import { formatMoney } from './currency';

/**
 * Shows money in the currency the customer chose on the preferences page. Cash and totals are held in rupees; with
 * dollars chosen they are divided by the stored mid USD/INR rate, the same daily rate the platform converts orders
 * with (without the order margin). The prices of individual instruments are never converted: a share quoted in
 * dollars keeps its dollar price.
 *
 * <p>If the preference or the rate has not loaded, amounts are shown in their own currency, so a figure is never
 * shown under the wrong symbol.
 */
@Injectable({ providedIn: 'root' })
export class DisplayCurrencyService {
  private readonly auth = inject(AuthService);
  private readonly preferences = inject(PreferencesService);
  private readonly watchlists = inject(WatchlistsService);

  /** The chosen display currency, INR until the preference has loaded. */
  readonly preferred = signal<string>('INR');
  private readonly midRate = signal<number | null>(null);
  private started = false;

  /** Loads the preference and the rate once. Safe to call from every page that shows money. */
  ensureLoaded(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    this.auth.getProfile().subscribe({
      next: (profile) => this.preferences.getPreferences(profile.accountId).subscribe({
        next: (preference) => this.preferred.set(preference.displayCurrency || 'INR'),
        error: () => undefined
      }),
      error: () => {
        this.started = false;
      }
    });
    this.watchlists.getFxRates().subscribe({
      next: (rates) => this.midRate.set(Number(rates.midRate)),
      error: () => undefined
    });
  }

  /** Called when the customer saves a new choice on the preferences page. */
  setPreferred(currency: string): void {
    this.preferred.set(currency || 'INR');
  }

  /** Forget everything, so the next customer to sign in on this browser loads their own preference. */
  reset(): void {
    this.started = false;
    this.preferred.set('INR');
    this.midRate.set(null);
  }

  /** The amount in the display currency when it can be converted, else unchanged in its own currency. */
  convert(amount: number, from: string): { amount: number; currency: string } {
    const to = this.preferred();
    const mid = this.midRate();
    if (from === to || !mid) {
      return { amount, currency: from };
    }
    if (from === 'INR' && to === 'USD') {
      return { amount: amount / mid, currency: 'USD' };
    }
    if (from === 'USD' && to === 'INR') {
      return { amount: amount * mid, currency: 'INR' };
    }
    return { amount, currency: from };
  }

  /** Money in the display currency: ₹1,234.56 or $14.81. */
  show(amount: number | string | null | undefined, from: string, digits = 2): string {
    if (amount === null || amount === undefined || amount === '') {
      return '—';
    }
    const converted = this.convert(Number(amount), from);
    return formatMoney(converted.amount, converted.currency, digits);
  }

  /** The same with a leading plus on gains: +₹1,000.00. */
  showSigned(amount: number | string | null | undefined, from: string, digits = 2): string {
    if (amount === null || amount === undefined || amount === '') {
      return '—';
    }
    const converted = this.convert(Number(amount), from);
    return formatMoney(converted.amount, converted.currency, digits, true);
  }
}
