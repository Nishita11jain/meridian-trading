import { Component, Input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Profile } from './profile';
import { AppShell } from '../../shared/layout/app-shell';
import { AuthService } from '../../core/auth/auth.service';
import { AccountsService } from '../../clients/trade-client/api/accounts.service';
import { PreferencesService } from '../../core/preferences/preferences.service';
import { AvatarStore } from '../../core/profile/avatar.store';
import { ThemeService } from '../../core/theme/theme.service';

/** The real shell needs notifications and the assistant; the profile page is tested without them. */
@Component({ selector: 'app-shell', template: '<ng-content></ng-content>' })
class StubShell {
  @Input() title = '';
}

const preference = (avatarKey: string | null) => ({
  accountId: 4,
  defaultAccountId: 4,
  notificationChannel: 'IN_APP',
  contactEmail: null,
  contactPhone: null,
  displayCurrency: 'INR',
  theme: 'SYSTEM',
  avatarKey,
  updatedOn: '2026-10-01T00:00:00Z'
});

describe('Profile', () => {
  let auth: { getProfile: ReturnType<typeof vi.fn> };
  let accounts: { getAccount: ReturnType<typeof vi.fn> };
  let prefs: { getPreferences: ReturnType<typeof vi.fn>; updateAvatar: ReturnType<typeof vi.fn>; updateNotification: ReturnType<typeof vi.fn>; updateDisplay: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    auth = {
      getProfile: vi.fn().mockReturnValue(of({ id: 'u', username: 'parthasarathi.bhattacharya', accountId: 4, roles: ['CUSTOMER'], createdOn: '2026-06-30T09:00:00Z' }))
    };
    accounts = {
      getAccount: vi.fn().mockReturnValue(of({ id: 4, accountId: 'ETP-4101', holderName: 'Parthasarathi Bhattacharya', status: 'ACTIVE' }))
    };
    prefs = {
      getPreferences: vi.fn().mockReturnValue(of(preference('avatar_02'))),
      updateAvatar: vi.fn().mockImplementation((_id: number, key: string) => of(preference(key))),
      updateNotification: vi.fn().mockReturnValue(of(preference('avatar_02'))),
      updateDisplay: vi.fn().mockReturnValue(of(preference('avatar_02')))
    };

    await TestBed.configureTestingModule({
      imports: [Profile],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
        { provide: AccountsService, useValue: accounts },
        { provide: PreferencesService, useValue: prefs },
        { provide: ThemeService, useValue: { set: vi.fn(), dark: vi.fn().mockReturnValue(false) } }
      ]
    })
      .overrideComponent(Profile, { remove: { imports: [AppShell] }, add: { imports: [StubShell] } })
      .compileComponents();
  });

  function render() {
    const fixture = TestBed.createComponent(Profile);
    fixture.detectChanges();
    return fixture;
  }

  const swatch = (fixture: { nativeElement: HTMLElement }, key: string) =>
    fixture.nativeElement.querySelector(`[data-testid="avatar-${key}"]`) as HTMLButtonElement;
  const saveButton = (fixture: { nativeElement: HTMLElement }) =>
    fixture.nativeElement.querySelector('[data-testid="avatar-save"]') as HTMLButtonElement;

  it('shows the account holder name as the heading, with the username underneath', () => {
    const root: HTMLElement = render().nativeElement;

    expect(root.querySelector('[data-testid="profile-name"]')?.textContent).toContain('Parthasarathi Bhattacharya');
    expect(root.querySelector('[data-testid="profile-username"]')?.textContent).toContain('parthasarathi.bhattacharya');
  });

  it('shows the holder and account reference from the accounts table', () => {
    const root: HTMLElement = render().nativeElement;

    expect(root.querySelector('[data-testid="profile-holder"]')?.textContent).toContain('Parthasarathi Bhattacharya');
    expect(root.querySelector('[data-testid="profile-account"]')?.textContent).toContain('ETP-4101');
  });

  it('shows no balance, cash or session figure on the profile', () => {
    expect(render().nativeElement.textContent).not.toMatch(/cash|balance|session/i);
  });

  it('starts on the avatar the server has stored', () => {
    const fixture = render();

    expect(TestBed.inject(AvatarStore).savedKey()).toBe('avatar_02');
    expect(swatch(fixture, 'avatar_02').getAttribute('aria-checked')).toBe('true');
  });

  it('picking a swatch does not save anything: the top bar keeps the old avatar until Save', () => {
    const fixture = render();

    swatch(fixture, 'avatar_04').click();
    fixture.detectChanges();

    expect(prefs.updateAvatar).not.toHaveBeenCalled();
    expect(TestBed.inject(AvatarStore).savedKey()).toBe('avatar_02');
    expect(saveButton(fixture).disabled).toBe(false);
  });

  it('Save stores the chosen avatar on the server and updates the top bar', () => {
    const fixture = render();

    swatch(fixture, 'avatar_04').click();
    fixture.detectChanges();
    saveButton(fixture).click();
    fixture.detectChanges();

    expect(prefs.updateAvatar).toHaveBeenCalledWith(4, 'avatar_04');
    expect(TestBed.inject(AvatarStore).savedKey()).toBe('avatar_04');
    expect(fixture.nativeElement.querySelector('[data-testid="profile-avatar-saved"]')).not.toBeNull();
  });

  it('Save is disabled until the picked avatar differs from the saved one', () => {
    const fixture = render();

    expect(saveButton(fixture).disabled).toBe(true);
  });

  it('Cancel puts the saved avatar back without saving', () => {
    const fixture = render();

    swatch(fixture, 'avatar_05').click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[data-testid="avatar-cancel"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(prefs.updateAvatar).not.toHaveBeenCalled();
    expect(swatch(fixture, 'avatar_02').getAttribute('aria-checked')).toBe('true');
  });

  it('a failed save keeps the saved avatar, keeps the pick and says so', () => {
    prefs.updateAvatar.mockReturnValue(throwError(() => new Error('down')));
    const fixture = render();

    swatch(fixture, 'avatar_06').click();
    fixture.detectChanges();
    saveButton(fixture).click();
    fixture.detectChanges();

    expect(TestBed.inject(AvatarStore).savedKey()).toBe('avatar_02');
    expect(fixture.componentInstance.pendingKey()).toBe('avatar_06');
    expect(fixture.componentInstance.errorMessage()).not.toBe('');
  });

  it('nothing is kept in local storage', () => {
    localStorage.clear();
    const fixture = render();

    swatch(fixture, 'avatar_06').click();
    saveButton(fixture).click();

    expect(localStorage.getItem('ledger.avatar')).toBeNull();
  });

  it('includes the preferences in the profile, with no separate preferences page', () => {
    const root: HTMLElement = render().nativeElement;

    expect(root.querySelector('app-preferences-settings')).not.toBeNull();
    expect(root.querySelector('[data-testid="preferences-channel"]')).not.toBeNull();
  });

  it('a failed account lookup shows a plain message', () => {
    accounts.getAccount.mockReturnValue(throwError(() => new Error('down')));

    expect(render().componentInstance.errorMessage()).not.toBe('');
  });
});
