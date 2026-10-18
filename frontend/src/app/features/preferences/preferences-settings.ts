import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';

import { AuthService } from '../../core/auth/auth.service';
import { PreferencesService } from '../../core/preferences/preferences.service';
import { DisplayCurrencyService } from '../../core/currency/display-currency.service';
import { ThemeService } from '../../core/theme/theme.service';
import { messageFor } from '../../core/errors/error-messages';
import { PreferenceResponse } from '../../clients/preferences-client/model/models';

/**
 * Customer preferences: the notification channel with its contact, and the display currency with the UI
 * theme. Shown inside the profile page. Two independent groups, saved separately so one cannot overwrite
 * the other. The theme setting drives the app's light/dark toggle. Amounts are not converted between
 * currencies; the display currency only changes how totals are labelled and formatted.
 */
@Component({
  selector: 'app-preferences-settings',
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <!-- Rendered inside the profile page's Preferences card, so its sections are divided, not boxed again. -->
    <div class="grid gap-4">
      <div
        class="lt-alert lt-alert-down"
        data-testid="preferences-error" role="alert" *ngIf="errorMessage()"
      >{{ errorMessage() }}</div>

      <div
        class="lt-alert lt-alert-up"
        data-testid="preferences-saved" role="status" *ngIf="savedMessage()"
      >{{ savedMessage() }}</div>

      <section class="pt-4" style="border-top: 1px solid var(--border)">
        <h2 class="lt-section-title mb-1">Notifications</h2>
        <p class="mb-4 text-sm" style="color: var(--muted)">How we reach you about your orders and alerts.</p>

        <form [formGroup]="notificationForm" (ngSubmit)="saveNotification()" novalidate class="grid gap-4">
          <label class="block">
            <span class="lt-field-label">Channel</span>
            <select class="lt-select"
              data-testid="preferences-channel" formControlName="notificationChannel">
              <option value="IN_APP">In-app</option>
              <option value="EMAIL">Email</option>
              <option value="SMS">SMS</option>
              <option value="PUSH">Push</option>
            </select>
          </label>

          <label class="block" *ngIf="notificationForm.controls.notificationChannel.value === 'EMAIL'">
            <span class="lt-field-label">Email address</span>
            <input class="lt-input"
              data-testid="preferences-email" type="email" formControlName="contactEmail" placeholder="you@example.com" />
          </label>

          <label class="block" *ngIf="notificationForm.controls.notificationChannel.value === 'SMS'">
            <span class="lt-field-label">Phone number</span>
            <input class="lt-input"
              data-testid="preferences-phone" type="tel" formControlName="contactPhone" placeholder="+91 98765 43210" />
          </label>

          <div>
            <button type="submit" data-testid="preferences-save-notifications"
              class="lt-btn lt-btn-primary"
              [disabled]="savingNotification()">
              {{ savingNotification() ? 'Saving...' : 'Save notifications' }}
            </button>
          </div>
        </form>
      </section>

      <section class="pt-4" style="border-top: 1px solid var(--border)">
        <h2 class="lt-section-title mb-1">Display</h2>
        <p class="mb-4 text-sm" style="color: var(--muted)">
          The currency totals are shown in, and the theme. Totals are converted at the day's rate; a share keeps the currency it trades in.
        </p>

        <form [formGroup]="displayForm" (ngSubmit)="saveDisplay()" novalidate class="grid gap-4">
          <label class="block">
            <span class="lt-field-label">Display currency</span>
            <select class="lt-select"
              data-testid="preferences-currency" formControlName="displayCurrency">
              <option *ngFor="let currency of currencies" [value]="currency">{{ currency }}</option>
            </select>
          </label>

          <label class="block">
            <span class="lt-field-label">Theme</span>
            <select class="lt-select"
              data-testid="preferences-theme" formControlName="theme">
              <option value="LIGHT">Light</option>
              <option value="DARK">Dark</option>
              <option value="SYSTEM">Match system</option>
            </select>
          </label>

          <div>
            <button type="submit" data-testid="preferences-save-display"
              class="lt-btn lt-btn-primary"
              [disabled]="savingDisplay()">
              {{ savingDisplay() ? 'Saving...' : 'Save display' }}
            </button>
          </div>
        </form>
      </section>
    </div>
  `
})
export class PreferencesSettings implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly preferences = inject(PreferencesService);
  private readonly theme = inject(ThemeService);
  private readonly displayCurrency = inject(DisplayCurrencyService);

  // The currencies the platform trades in. The server is the real gate (it validates against the
  // instrument catalogue); this list is the convenience picker, matching the seeded instruments.
  readonly currencies = ['INR', 'USD'];

  private readonly accountId = signal<number | null>(null);
  readonly savingNotification = signal(false);
  readonly savingDisplay = signal(false);
  readonly errorMessage = signal('');
  readonly savedMessage = signal('');

  readonly notificationForm = this.fb.nonNullable.group({
    notificationChannel: ['IN_APP', Validators.required],
    contactEmail: [''],
    contactPhone: ['']
  });

  readonly displayForm = this.fb.nonNullable.group({
    displayCurrency: ['INR', Validators.required],
    theme: ['SYSTEM', Validators.required]
  });

  ngOnInit(): void {
    this.auth.getProfile().subscribe({
      next: (profile) => {
        this.accountId.set(profile.accountId);
        this.load(profile.accountId);
      },
      error: (err) => this.errorMessage.set(messageFor(err))
    });
  }

  private load(accountId: number): void {
    this.preferences.getPreferences(accountId).subscribe({
      next: (preference) => this.apply(preference),
      error: (err) => this.errorMessage.set(messageFor(err))
    });
  }

  private apply(preference: PreferenceResponse): void {
    this.notificationForm.patchValue({
      notificationChannel: preference.notificationChannel,
      contactEmail: preference.contactEmail ?? '',
      contactPhone: preference.contactPhone ?? ''
    });
    this.displayForm.patchValue({
      displayCurrency: preference.displayCurrency,
      theme: preference.theme
    });
    this.applyTheme(preference.theme);
  }

  saveNotification(): void {
    const accountId = this.accountId();
    if (accountId === null || this.savingNotification()) {
      return;
    }
    this.clearBanners();
    this.savingNotification.set(true);
    const value = this.notificationForm.getRawValue();
    this.preferences.updateNotification(accountId, {
      notificationChannel: value.notificationChannel as never,
      contactEmail: value.contactEmail || undefined,
      contactPhone: value.contactPhone || undefined
    }).subscribe({
      next: (preference) => {
        this.savingNotification.set(false);
        this.apply(preference);
        this.savedMessage.set('Notification settings saved.');
      },
      error: (err) => {
        this.savingNotification.set(false);
        this.errorMessage.set(messageFor(err));
      }
    });
  }

  saveDisplay(): void {
    const accountId = this.accountId();
    if (accountId === null || this.savingDisplay()) {
      return;
    }
    this.clearBanners();
    this.savingDisplay.set(true);
    const value = this.displayForm.getRawValue();
    this.preferences.updateDisplay(accountId, {
      displayCurrency: value.displayCurrency,
      theme: value.theme as never
    }).subscribe({
      next: (preference) => {
        this.savingDisplay.set(false);
        this.apply(preference);
        this.displayCurrency.setPreferred(preference.displayCurrency);
        this.savedMessage.set('Display settings saved.');
      },
      error: (err) => {
        this.savingDisplay.set(false);
        this.errorMessage.set(messageFor(err));
      }
    });
  }

  /** The stored theme drives the app's existing light/dark toggle. SYSTEM follows the OS setting. */
  private applyTheme(theme: string): void {
    if (theme === 'LIGHT') {
      this.theme.set(false);
    } else if (theme === 'DARK') {
      this.theme.set(true);
    } else {
      const prefersDark = typeof window !== 'undefined'
        && typeof window.matchMedia === 'function'
        && window.matchMedia('(prefers-color-scheme: dark)').matches;
      this.theme.set(prefersDark);
    }
  }

  private clearBanners(): void {
    this.errorMessage.set('');
    this.savedMessage.set('');
  }
}
