import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

import { AuthService } from '../../core/auth/auth.service';
import { PreferencesService } from '../../core/preferences/preferences.service';
import { AVATARS, AvatarStore } from '../../core/profile/avatar.store';
import { messageFor } from '../../core/errors/error-messages';
import { AccountsService } from '../../clients/trade-client/api/accounts.service';
import { AccountResponse } from '../../clients/trade-client/model/models';
import { AppShell } from '../../shared/layout/app-shell';
import { PreferencesSettings } from '../preferences/preferences-settings';

/**
 * The profile page: who the user is, their trading account, and their preferences. Only what a customer
 * needs; there are no investor-profile or identity-document fields, and no cash or balance figures.
 *
 * Choosing an avatar only previews it. It is saved, and then shown in the top bar, when the user presses
 * Save. Cancel puts the saved avatar back.
 */
@Component({
  selector: 'app-profile',
  imports: [CommonModule, AppShell, PreferencesSettings],
  template: `
    <app-shell title="Profile">
      <div class="profile" style="max-width: 1040px">
        @if (errorMessage()) {
          <div class="profile__alert" data-testid="profile-error" role="alert">{{ errorMessage() }}</div>
        }
        @if (savedMessage()) {
          <div class="profile__saved" data-testid="profile-avatar-saved" role="status">{{ savedMessage() }}</div>
        }

        <section class="profile__hero lt-card" data-testid="profile-identity">
          <div class="profile__identity">
            <span class="profile__avatar" data-testid="profile-avatar"
              [style.background]="avatars.savedColor()">{{ initial() }}</span>
            <div>
              <p class="profile__eyebrow">Signed in as</p>
              <h2 class="profile__name" data-testid="profile-name">{{ displayName() }}</h2>
              <p class="profile__muted" data-testid="profile-username">Username {{ username() }}</p>
              @if (memberSince()) {
                <p class="profile__muted">Member since {{ memberSince() }}</p>
              }
            </div>
          </div>

          <div class="profile__avatars">
            <p class="profile__label">Avatar</p>
            <div class="profile__swatches" role="radiogroup" aria-label="Avatar">
              @for (avatar of avatarOptions; track avatar.key) {
                <button type="button" role="radio" class="profile__swatch"
                  [class.profile__swatch--on]="avatar.key === pendingKey()"
                  [attr.aria-checked]="avatar.key === pendingKey()"
                  [attr.aria-label]="avatar.label"
                  [attr.data-testid]="'avatar-' + avatar.key"
                  [style.background]="avatar.color"
                  (click)="pick(avatar.key)">{{ initial() }}</button>
              }
            </div>
            <div class="profile__actions">
              <button type="button" class="lt-btn lt-btn-primary" data-testid="avatar-save"
                [disabled]="!hasChange() || saving()" (click)="saveAvatar()">
                {{ saving() ? 'Saving...' : 'Save avatar' }}
              </button>
              @if (hasChange()) {
                <button type="button" class="lt-btn lt-btn-ghost" data-testid="avatar-cancel"
                  [disabled]="saving()" (click)="cancelAvatar()">Cancel</button>
              }
            </div>
          </div>
        </section>

        <div class="profile__grid">
          <section class="lt-card profile__card" data-testid="profile-account">
            <h3 class="profile__title">Trading account</h3>
            @if (account(); as acc) {
              <dl class="profile__rows">
                <div class="profile__row"><dt>Account holder</dt><dd data-testid="profile-holder">{{ acc.holderName }}</dd></div>
                <div class="profile__row"><dt>Account reference</dt><dd>{{ acc.accountId }}</dd></div>
                <div class="profile__row"><dt>Status</dt><dd><span class="profile__status" [attr.data-status]="acc.status">{{ statusText(acc.status) }}</span></dd></div>
                <div class="profile__row"><dt>Base currency</dt><dd>INR</dd></div>
              </dl>
            } @else {
              <p class="profile__muted">Loading your account...</p>
            }
          </section>

          <section class="lt-card profile__card" data-testid="profile-preferences-card">
            <h3 class="profile__title">Preferences</h3>
            <p class="profile__muted">How we reach you, how amounts are shown and which theme you use.</p>
            <app-preferences-settings></app-preferences-settings>
          </section>
        </div>
      </div>
    </app-shell>
  `,
  styles: [`
    .profile { display: grid; gap: 20px; }
    .profile__alert {
      padding: 12px 16px; border-radius: 12px; font-weight: 600;
      background: var(--down-bg); color: var(--down);
    }
    .profile__saved {
      padding: 12px 16px; border-radius: 12px; font-weight: 600;
      background: var(--up-bg); color: var(--up);
    }
    .profile__hero {
      display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 24px;
      padding: 24px 28px;
    }
    .profile__identity { display: flex; align-items: center; gap: 18px; }
    .profile__avatar {
      width: 72px; height: 72px; border-radius: 50%; color: #fff; font-size: 26px; font-weight: 800;
      display: grid; place-items: center; box-shadow: 0 8px 22px rgba(15, 23, 42, 0.18);
    }
    .profile__eyebrow { margin: 0; font-size: 12px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--muted); }
    .profile__name { margin: 2px 0 2px; font-size: 22px; font-weight: 800; }
    .profile__muted { margin: 0; font-size: 13px; color: var(--muted); }
    .profile__label { margin: 0 0 8px; font-size: 12px; font-weight: 600; color: var(--muted); }
    .profile__swatches { display: flex; flex-wrap: wrap; gap: 10px; }
    .profile__swatch {
      width: 38px; height: 38px; border-radius: 50%; border: 2px solid transparent; color: #fff;
      font-weight: 700; cursor: pointer; transition: transform 120ms ease;
    }
    .profile__swatch:hover { transform: scale(1.08); }
    .profile__swatch--on { border-color: var(--surface); box-shadow: 0 0 0 3px var(--accent); }
    .profile__swatch:focus-visible { outline: 3px solid var(--accent); outline-offset: 2px; }
    .profile__actions { display: flex; gap: 10px; margin-top: 14px; }

    .profile__grid { display: grid; gap: 20px; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); align-items: start; }
    .profile__card { padding: 22px 24px; }
    .profile__title { margin: 0 0 14px; font-size: 16px; font-weight: 800; }
    .profile__rows { margin: 0; display: grid; gap: 0; }
    .profile__row {
      display: flex; justify-content: space-between; gap: 16px; padding: 12px 0;
      border-bottom: 1px solid var(--border); font-size: 14px;
    }
    .profile__row:last-child { border-bottom: none; }
    .profile__row dt { color: var(--muted); }
    .profile__row dd { margin: 0; font-weight: 600; text-align: right; }
    .profile__status {
      display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 12px; font-weight: 700;
      background: var(--up-bg); color: var(--up);
    }
    .profile__status[data-status='SUSPENDED'] { background: var(--down-bg); color: var(--down); }
    .profile__status[data-status='CLOSED'] { background: var(--surface-muted, #f3f4f6); color: var(--muted); }
    @media (max-width: 600px) {
      .profile__hero { padding: 18px; }
      .profile__grid { grid-template-columns: 1fr; }
    }
  `]
})
export class Profile implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly accounts = inject(AccountsService);
  private readonly preferences = inject(PreferencesService);
  protected readonly avatars = inject(AvatarStore);

  readonly avatarOptions = AVATARS;
  readonly username = signal('');
  readonly memberSince = signal('');
  readonly account = signal<AccountResponse | null>(null);
  /** The swatch the user has picked but not yet saved. */
  readonly pendingKey = signal(AVATARS[0].key);
  readonly saving = signal(false);
  readonly errorMessage = signal('');
  readonly savedMessage = signal('');

  /** The account holder's name from the accounts table; the login username only when no account is loaded yet. */
  readonly displayName = () => this.account()?.holderName || this.username();
  readonly initial = () => (this.displayName().charAt(0) || '?').toUpperCase();
  readonly hasChange = () => this.pendingKey() !== this.avatars.savedKey();

  private accountId: number | null = null;

  ngOnInit(): void {
    this.auth.getProfile().subscribe({
      next: (profile) => {
        this.accountId = profile.accountId;
        this.username.set(profile.username);
        this.memberSince.set(profile.createdOn
          ? new Date(profile.createdOn).toLocaleDateString('en-IN', { year: 'numeric', month: 'long', day: 'numeric' })
          : '');
        this.loadAvatar(profile.accountId);
        this.accounts.getAccount(profile.accountId).subscribe({
          next: (acc) => this.account.set(acc),
          error: (err) => this.errorMessage.set(messageFor(err))
        });
      },
      error: (err) => this.errorMessage.set(messageFor(err))
    });
  }

  pick(key: string): void {
    this.pendingKey.set(key);
    this.savedMessage.set('');
  }

  cancelAvatar(): void {
    this.pendingKey.set(this.avatars.savedKey());
    this.errorMessage.set('');
  }

  saveAvatar(): void {
    if (this.accountId === null || !this.hasChange() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.errorMessage.set('');
    this.savedMessage.set('');
    this.preferences.updateAvatar(this.accountId, this.pendingKey()).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.avatars.set(saved.avatarKey ?? this.pendingKey());
        this.pendingKey.set(this.avatars.savedKey());
        this.savedMessage.set('Avatar saved.');
      },
      error: (err) => {
        // The saved avatar stays as it was, and the picker keeps the choice so the user can try again.
        this.saving.set(false);
        this.errorMessage.set(messageFor(err));
      }
    });
  }

  /** ACTIVE reads as Active. A missing status shows a dash rather than throwing, which would stop the whole page rendering. */
  statusText(status: string | null | undefined): string {
    return status ? status.charAt(0) + status.slice(1).toLowerCase() : '—';
  }

  private loadAvatar(accountId: number): void {
    this.preferences.getPreferences(accountId).subscribe({
      next: (preference) => {
        if (preference.avatarKey) {
          this.avatars.set(preference.avatarKey);
          this.pendingKey.set(preference.avatarKey);
        }
      },
      error: (err) => this.errorMessage.set(messageFor(err))
    });
  }
}
