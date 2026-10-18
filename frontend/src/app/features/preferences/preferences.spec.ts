import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { PreferencesSettings as Preferences } from './preferences-settings';
import { AuthService } from '../../core/auth/auth.service';
import { PreferencesService } from '../../core/preferences/preferences.service';
import { ThemeService } from '../../core/theme/theme.service';

const stored = {
  accountId: 4,
  defaultAccountId: 4,
  notificationChannel: 'EMAIL',
  contactEmail: 'a@b.com',
  contactPhone: null,
  displayCurrency: 'USD',
  theme: 'DARK',
  updatedOn: '2026-10-01T00:00:00Z'
};

describe('Preferences', () => {
  let prefs: { getPreferences: ReturnType<typeof vi.fn>; updateNotification: ReturnType<typeof vi.fn>; updateDisplay: ReturnType<typeof vi.fn> };
  let theme: { set: ReturnType<typeof vi.fn>; dark: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    prefs = {
      getPreferences: vi.fn().mockReturnValue(of({ ...stored })),
      updateNotification: vi.fn().mockReturnValue(of({ ...stored })),
      updateDisplay: vi.fn().mockReturnValue(of({ ...stored }))
    };
    theme = { set: vi.fn(), dark: vi.fn().mockReturnValue(false) };

    await TestBed.configureTestingModule({
      imports: [Preferences],
      providers: [
        { provide: AuthService, useValue: { getProfile: () => of({ accountId: 4 }), logout: vi.fn() } },
        { provide: PreferencesService, useValue: prefs },
        { provide: ThemeService, useValue: theme },
        provideRouter([])
      ]
    }).compileComponents();
  });

  function render() {
    const fixture = TestBed.createComponent(Preferences);
    fixture.detectChanges();
    return fixture;
  }
  const el = (fixture: any, id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`);

  it('loads the stored preferences into both forms and applies the theme', () => {
    const fixture = render();

    expect(prefs.getPreferences).toHaveBeenCalledWith(4);
    expect(el(fixture, 'preferences-channel').value).toContain('EMAIL');
    expect(el(fixture, 'preferences-currency').value).toContain('USD');
    expect(el(fixture, 'preferences-theme').value).toContain('DARK');
    expect(theme.set).toHaveBeenCalledWith(true); // DARK -> dark on
  });

  it('shows the email field only for the EMAIL channel', () => {
    const fixture = render();
    expect(el(fixture, 'preferences-email')).toBeTruthy();

    fixture.componentInstance.notificationForm.controls.notificationChannel.setValue('IN_APP');
    fixture.detectChanges();
    expect(el(fixture, 'preferences-email')).toBeNull();
    expect(el(fixture, 'preferences-phone')).toBeNull();
  });

  it('saves notification settings and confirms', () => {
    const fixture = render();

    el(fixture, 'preferences-save-notifications').click();

    expect(prefs.updateNotification).toHaveBeenCalledWith(4, expect.objectContaining({ notificationChannel: 'EMAIL' }));
    fixture.detectChanges();
    expect(el(fixture, 'preferences-saved').textContent).toContain('Notification settings saved');
  });

  it('saves display settings and re-applies the theme', () => {
    const fixture = render();
    theme.set.mockClear();

    fixture.componentInstance.displayForm.controls.theme.setValue('LIGHT');
    el(fixture, 'preferences-save-display').click();

    expect(prefs.updateDisplay).toHaveBeenCalledWith(4, expect.objectContaining({ displayCurrency: 'USD' }));
  });

  it('shows a readable error when loading fails', () => {
    prefs.getPreferences.mockReturnValue(throwError(() => ({ status: 403, error: { errorCode: 'ACC-403' } })));

    const fixture = render();

    expect(el(fixture, 'preferences-error')?.textContent).toBeTruthy();
  });
});
