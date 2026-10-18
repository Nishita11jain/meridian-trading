import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { PreferencesService } from '../preferences/preferences.service';
import { WatchlistsService } from '../watchlists/watchlists.service';
import { DisplayCurrencyService } from './display-currency.service';

describe('DisplayCurrencyService', () => {
  let preferences: { getPreferences: ReturnType<typeof vi.fn> };
  let watchlists: { getFxRates: ReturnType<typeof vi.fn> };

  function create(preferred: string, fx: any = { midRate: 80 }) {
    preferences = { getPreferences: vi.fn().mockReturnValue(of({ displayCurrency: preferred })) };
    watchlists = { getFxRates: vi.fn().mockReturnValue(fx === 'fail' ? throwError(() => ({ status: 503 })) : of(fx)) };
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { getProfile: () => of({ accountId: 4 }) } },
        { provide: PreferencesService, useValue: preferences },
        { provide: WatchlistsService, useValue: watchlists }
      ]
    });
    const service = TestBed.inject(DisplayCurrencyService);
    service.ensureLoaded();
    return service;
  }

  it('shows rupee amounts as they are when rupees are chosen', () => {
    const service = create('INR');

    expect(service.preferred()).toBe('INR');
    expect(service.show(1000, 'INR')).toBe('₹1,000.00');
  });

  it('converts rupees to dollars at the stored mid rate when dollars are chosen', () => {
    const service = create('USD');

    expect(service.preferred()).toBe('USD');
    expect(service.show(8000, 'INR')).toBe('$100.00');
    expect(service.showSigned(-800, 'INR')).toBe('-$10.00');
  });

  it('converts a dollar amount to rupees when rupees are chosen', () => {
    const service = create('INR');

    expect(service.show(10, 'USD')).toBe('₹800.00');
  });

  it('leaves an amount alone when it is already in the chosen currency', () => {
    const service = create('USD');

    expect(service.show(12.34, 'USD')).toBe('$12.34');
  });

  it('shows amounts in their own currency while the rate is unavailable, never under the wrong symbol', () => {
    const service = create('USD', 'fail');

    expect(service.show(8000, 'INR')).toBe('₹8,000.00');
  });

  it('picks up a new choice straight away', () => {
    const service = create('INR');

    service.setPreferred('USD');

    expect(service.show(8000, 'INR')).toBe('$100.00');
  });

  it('loads only once, and again after a reset for the next customer', () => {
    const service = create('USD');
    service.ensureLoaded();
    expect(preferences.getPreferences).toHaveBeenCalledTimes(1);

    service.reset();
    expect(service.preferred()).toBe('INR');
    service.ensureLoaded();
    expect(preferences.getPreferences).toHaveBeenCalledTimes(2);
  });

  it('shows a dash for an amount that is missing', () => {
    const service = create('INR');

    expect(service.show(null, 'INR')).toBe('—');
    expect(service.showSigned(undefined, 'USD')).toBe('—');
  });
});
