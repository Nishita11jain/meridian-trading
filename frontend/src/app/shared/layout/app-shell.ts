import { Component, ElementRef, HostListener, Input, OnDestroy, OnInit, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ThemeService } from '../../core/theme/theme.service';
import { NotificationsStore } from '../../core/notifications/notifications.store';
import { NotificationDetail } from '../notifications/notification-detail';
import { NotificationResponse } from '../../clients/notifications-client/model/models';
import { AssistantPanel } from '../assistant/assistant-panel';
import { PreferencesService } from '../../core/preferences/preferences.service';
import { AvatarStore } from '../../core/profile/avatar.store';

/**
 * The application shell for every authenticated page: a branded sidebar with grouped navigation, and a
 * top bar carrying the page title, a greeting, the theme toggle and sign-out. Page content is projected
 * into <main>. The shell owns navigation and session chrome so no screen repeats it.
 */
@Component({
  selector: 'app-shell',
  imports: [CommonModule, RouterLink, RouterLinkActive, NotificationDetail, AssistantPanel],
  template: `
    <a href="#main-content" class="skip-link" data-testid="skip-link" (click)="skipToContent($event)">Skip to content</a>

    <div class="lt-terminal-shell">
      <aside class="lt-sidebar">
        <div>
          <a routerLink="/dashboard" class="lt-sidebar__brand" aria-label="Open dashboard">
            <span class="lt-sidebar__brand-mark">M</span>
            <span>
              <span class="lt-sidebar__brand-name">Meridian</span>
              <span class="lt-sidebar__brand-copy">Trading desk</span>
            </span>
          </a>

          <nav class="lt-sidebar__nav" aria-label="Primary">
            <p class="lt-eyebrow lt-sidebar__section-label">Trading</p>
            <ng-container *ngTemplateOutlet="navLink; context: { link: '/dashboard', id: 'nav-dashboard', label: 'Dashboard', note: 'Overview', icon: 'grid', exact: true }"></ng-container>
            <ng-container *ngTemplateOutlet="navLink; context: { link: '/trade', id: 'nav-trade', label: 'New order', note: 'Ticket', icon: 'plus' }"></ng-container>
            <ng-container *ngTemplateOutlet="navLink; context: { link: '/blotter', id: 'nav-blotter', label: 'Blotter', note: 'Orders', icon: 'list' }"></ng-container>
            <ng-container *ngTemplateOutlet="navLink; context: { link: '/portfolio', id: 'nav-portfolio', label: 'Portfolio', note: 'Exposure', icon: 'chart' }"></ng-container>
            <ng-container *ngTemplateOutlet="navLink; context: { link: '/statements', id: 'nav-statements', label: 'Statements', note: 'Trades and cash', icon: 'list' }"></ng-container>

            <p class="lt-eyebrow lt-sidebar__section-label">Markets</p>
            <ng-container *ngTemplateOutlet="navLink; context: { link: '/watchlists', id: 'nav-watchlists', label: 'Watchlists', note: 'Board', icon: 'star' }"></ng-container>
            <ng-container *ngTemplateOutlet="navLink; context: { link: '/advice', id: 'nav-advice', label: 'Trade advice', note: 'Signals', icon: 'chart' }"></ng-container>
            <ng-container *ngTemplateOutlet="navLink; context: { link: '/strategies', id: 'nav-strategies', label: 'Strategies', note: 'Automated', icon: 'cog' }"></ng-container>

          </nav>
        </div>

      </aside>

      <div class="lt-workspace">
        <header class="lt-workspace__topbar">
          <div class="lt-title-block">
            <p class="lt-eyebrow">Trading workspace</p>
            <div class="lt-title-row">
              <h1 class="lt-page-title">{{ title }}</h1>
            </div>
          </div>

          <div class="lt-command-deck">
            <div class="lt-notifications" data-notifications-root>
              <button
                type="button"
                #bell
                class="lt-icon-button lt-notifications__trigger"
                data-testid="nav-notifications"
                aria-haspopup="dialog"
                [attr.aria-expanded]="notificationsOpen()"
                [attr.aria-label]="notifications.unreadCount() > 0 ? notifications.unreadCount() + ' unread notifications' : 'Notifications'"
                (click)="toggleNotifications($event)"
              >
                <span class="lt-notifications__icon" [innerHTML]="iconFor('bell')"></span>
                <span
                  *ngIf="notifications.unreadCount() > 0"
                  data-testid="nav-notifications-badge"
                  class="lt-notifications__badge"
                >{{ notifications.unreadCount() }}</span>
              </button>

              <section *ngIf="notificationsOpen()" class="lt-card lt-notifications__panel" data-testid="notifications-panel" aria-label="Notifications">
                <header class="lt-notifications__panel-header">
                  <div>
                    <p class="lt-eyebrow">Notifications</p>
                    <h2 class="lt-notifications__panel-title">Inbox</h2>
                  </div>
                  <button
                    type="button"
                    class="lt-btn lt-btn-ghost lt-btn-sm"
                    data-testid="notifications-mark-read"
                    [disabled]="notifications.unreadCount() === 0 || accountId() === null"
                    (click)="markAllNotificationsRead()"
                  >Mark all read</button>
                </header>

                <div class="lt-notifications__panel-state lt-alert lt-alert-down m-3" *ngIf="notifications.errorMessage()" role="alert">{{ notifications.errorMessage() }}</div>

                <div class="lt-notifications__panel-state" *ngIf="!notifications.loaded() && !notifications.errorMessage()">
                  <p>Loading notifications...</p>
                </div>

                <div class="lt-notifications__panel-state" *ngIf="notifications.loaded() && notifications.notifications().length === 0 && !notifications.errorMessage()">
                  <p>No notifications yet.</p>
                </div>

                <ul class="lt-notifications__list" *ngIf="notifications.notifications().length > 0">
                  <li *ngFor="let notification of notifications.notifications(); trackBy: trackById" data-testid="notifications-row">
                    <!-- The whole item is one button: it opens the notification in a dialog. -->
                    <button type="button" class="lt-notifications__item lt-notifications__open" data-testid="notifications-open"
                      [style.border-color]="notification.readOn ? 'var(--border)' : 'var(--accent)'"
                      [style.background]="notification.readOn ? 'var(--surface)' : 'var(--accent-soft)'"
                      (click)="openNotification(notification, $event)">
                      <span class="lt-notifications__item-header">
                        <span class="lt-notifications__item-copy">
                          <span class="lt-notifications__item-subject">{{ notification.subject }}</span>
                        </span>
                        <span class="lt-notifications__item-meta">
                          <span class="lt-notifications__item-channel" data-testid="notifications-channel">{{ notification.channel }}</span>
                          <span *ngIf="!notification.readOn" class="lt-notifications__item-unread" data-testid="notifications-unread-dot" aria-hidden="true"></span>
                        </span>
                      </span>
                      <span class="lt-notifications__item-footer">
                        <span>{{ notification.createdOn | date:'medium' }}</span>
                        <span>{{ notification.readOn ? 'Read' : 'Unread' }}</span>
                      </span>
                    </button>
                  </li>
                </ul>
              </section>
            </div>
            <button
              type="button"
              class="lt-icon-button"
              data-testid="theme-toggle"
              [attr.aria-label]="theme.dark() ? 'Switch to light mode' : 'Switch to dark mode'"
              (click)="theme.toggle()"
            ><span class="lt-theme-glyph" aria-hidden="true">◐</span></button>
            <div class="account-menu">
              <button type="button" class="account-menu__trigger" data-testid="account-menu"
                aria-label="Account menu" aria-haspopup="menu" [attr.aria-expanded]="menuOpen()"
                [style.background]="avatars.savedColor()"
                (click)="toggleMenu($event)">{{ initial() }}</button>
              @if (menuOpen()) {
                <div class="account-menu__panel" role="menu" data-testid="account-menu-panel">
                  <div class="account-menu__who">
                    <strong data-testid="account-menu-username">{{ username() }}</strong>
                  </div>
                  <a class="account-menu__item" role="menuitem" routerLink="/profile" data-testid="menu-profile"
                    (click)="menuOpen.set(false)">Profile &amp; settings</a>
                  <button type="button" class="account-menu__item" role="menuitem" data-testid="signout"
                    (click)="onSignOut()">Sign out</button>
                </div>
              }
            </div>
          </div>
        </header>

        <main id="main-content" tabindex="-1" class="lt-workspace__main">
          <div class="lt-workspace__canvas">
            <ng-content></ng-content>
          </div>
        </main>
      </div>
    </div>

    <app-notification-detail *ngIf="openedNotification() as opened" [notification]="opened"
      [accountId]="accountId()!" (closed)="closeNotification()"></app-notification-detail>

    <app-assistant-panel></app-assistant-panel>

    <!-- One template for every nav link, so they stay identical. -->
    <ng-template #navLink let-link="link" let-id="id" let-label="label" let-note="note" let-icon="icon" let-exact="exact">
      <a
        [routerLink]="link"
        routerLinkActive
        #rla="routerLinkActive"
        [routerLinkActiveOptions]="{ exact: !!exact }"
        [attr.data-testid]="id"
        class="lt-nav-link"
        [class.lt-nav-link-active]="rla.isActive"
      >
        <span class="lt-nav-link__rail"></span>
        <span class="lt-nav-link__icon" [innerHTML]="iconFor(icon)"></span>
        <span class="lt-nav-link__body">
          <span class="lt-nav-link__label">{{ label }}</span>
          <span class="lt-nav-link__note">{{ note }}</span>
        </span>
      </a>
    </ng-template>
  `,
  styles: [`
    .account-menu { position: relative; }
    .account-menu__trigger {
      width: 36px; height: 36px; border-radius: 50%; border: none; cursor: pointer;
      background: var(--accent); color: #fff; font-weight: 700; font-size: 14px;
      box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .18);
    }
    .account-menu__trigger:focus-visible { outline: 3px solid var(--accent-soft); outline-offset: 2px; }
    .account-menu__panel {
      position: absolute; right: 0; top: calc(100% + 8px); z-index: 50; width: 240px;
      display: flex; flex-direction: column; padding: 6px;
      background: var(--surface); color: var(--text); border: 1px solid var(--border);
      border-radius: 12px; box-shadow: 0 14px 34px rgba(15, 23, 42, 0.18);
    }
    .account-menu__who { padding: 10px 12px 8px; border-bottom: 1px solid var(--border); margin-bottom: 4px; }
    .account-menu__item {
      display: block; width: 100%; text-align: left; padding: 9px 12px; border-radius: 8px;
      border: none; background: transparent; color: var(--text); font: inherit; font-size: 14px;
      text-decoration: none; cursor: pointer;
    }
    .account-menu__item:hover { background: var(--accent-soft); }
  `]
})
export class AppShell implements OnInit, OnDestroy {
  @Input() title = '';

  protected readonly theme = inject(ThemeService);
  protected readonly notifications = inject(NotificationsStore);
  private readonly authService = inject(AuthService);
  private readonly preferences = inject(PreferencesService);
  protected readonly avatars = inject(AvatarStore);
  private readonly router = inject(Router);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly iconCache = new Map<string, SafeHtml>();

  readonly accountId = signal<number | null>(null);
  readonly notificationsOpen = signal(false);
  /** The notification open in the detail dialog, if any. */
  readonly openedNotification = signal<NotificationResponse | null>(null);
  private readonly bell = viewChild<ElementRef<HTMLButtonElement>>('bell');
  private watchToken: number | null = null;
  readonly username = signal('');
  readonly menuOpen = signal(false);
  readonly initial = () => (this.username().charAt(0) || '?').toUpperCase();

  ngOnInit(): void {
    this.authService.getProfile().subscribe({
      next: (profile) => {
        this.accountId.set(profile.accountId);
        this.username.set(profile.username ?? '');
        // The top-bar avatar shows the saved choice, the same one the profile page shows.
        this.preferences.getPreferences(profile.accountId).subscribe({
          next: (preference) => {
            if (preference.avatarKey) {
              this.avatars.set(preference.avatarKey);
            }
          },
          error: () => undefined
        });
        // Keeps the badge current while the page is open, not only when a page loads.
        this.watchToken = this.notifications.watch(profile.accountId);
      },
      error: () => undefined
    });
  }

  ngOnDestroy(): void {
    if (this.watchToken !== null) {
      this.notifications.unwatch(this.watchToken);
    }
  }

  @HostListener('document:pointerdown', ['$event'])
  protected closeNotificationsOnOutsideClick(event: Event): void {
    const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
    const clickedInsideNotifications = path.some((node) =>
      node instanceof HTMLElement && node.hasAttribute('data-notifications-root'));
    if (!clickedInsideNotifications) {
      this.notificationsOpen.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  protected closeNotificationsOnEscape(): void {
    this.notificationsOpen.set(false);
  }

  protected trackById(_: number, notification: NotificationResponse): string {
    return notification.id;
  }

  /** Closes the panel and opens the notification in the dialog. */
  protected openNotification(notification: NotificationResponse, event: Event): void {
    event.stopPropagation();
    if (this.accountId() === null) {
      return;
    }
    this.notificationsOpen.set(false);
    this.openedNotification.set(notification);
  }

  /** The item that opened it was in the closed panel, so focus goes back to the bell. */
  protected closeNotification(): void {
    this.openedNotification.set(null);
    setTimeout(() => this.bell()?.nativeElement.focus());
  }

  /** Small inline icons (our own static SVG), so the sidebar needs no icon font or image requests. */
  protected iconFor(name: string): SafeHtml {
    const cached = this.iconCache.get(name);
    if (cached) {
      return cached;
    }
    const paths: Record<string, string> = {
      grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
      plus: '<circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8" stroke-linecap="round"/>',
      list: '<path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01" stroke-linecap="round"/>',
      chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2" stroke-linecap="round"/>',
      star: '<path d="M12 3l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9 6.8 19.2l1-5.8L3.5 9.2l5.9-.9L12 3z" stroke-linejoin="round"/>',
      bell: '<path d="M6 9a6 6 0 1112 0c0 5 2 6 2 6H4s2-1 2-6z" stroke-linejoin="round"/><path d="M9.5 20a2.5 2.5 0 005 0" stroke-linecap="round"/>',
      cog: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1" stroke-linecap="round"/>'
    };
    const svg = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">${paths[name] ?? ''}</svg>`;
    const safe = this.sanitizer.bypassSecurityTrustHtml(svg);
    this.iconCache.set(name, safe);
    return safe;
  }

  protected skipToContent(event: Event): void {
    event.preventDefault();
    document.getElementById('main-content')?.focus();
  }

  protected toggleNotifications(event: Event): void {
    event.stopPropagation();
    this.notificationsOpen.update((open) => !open);
    // Opening the panel shows the latest, without waiting for the next poll.
    const accountId = this.accountId();
    if (this.notificationsOpen() && accountId !== null) {
      this.notifications.load(accountId);
    }
  }

  protected markAllNotificationsRead(): void {
    const accountId = this.accountId();
    if (accountId !== null && this.notifications.unreadCount() > 0) {
      this.notifications.markAllRead(accountId);
    }
  }

  toggleMenu(event: Event): void {
    event.stopPropagation();
    this.menuOpen.update((open) => !open);
  }

  /** Any click outside the menu closes it; clicks inside stop at the trigger or the item. */
  @HostListener('document:click')
  protected closeMenu(): void {
    this.menuOpen.set(false);
  }

  onSignOut(): void {
    this.authService.logout();
    this.router.navigate(['/sign-in']);
  }
}
