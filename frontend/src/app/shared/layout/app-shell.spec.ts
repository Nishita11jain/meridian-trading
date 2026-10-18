import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AppShell } from './app-shell';
import { AuthService } from '../../core/auth/auth.service';
import { AssistantService } from '../../core/assistant/assistant.service';
import { PreferencesService } from '../../core/preferences/preferences.service';

import { of } from 'rxjs';
import { NotificationsStore } from '../../core/notifications/notifications.store';
import { NotificationsService } from '../../core/notifications/notifications.service';

describe('AppShell', () => {
  let mockAuthService: { logout: ReturnType<typeof vi.fn>; getProfile: ReturnType<typeof vi.fn> };
  let mockNotificationsStore: {
    load: ReturnType<typeof vi.fn>;
    watch: ReturnType<typeof vi.fn>;
    unwatch: ReturnType<typeof vi.fn>;
    unreadCount: ReturnType<typeof vi.fn>;
    notifications: ReturnType<typeof vi.fn>;
    loaded: ReturnType<typeof vi.fn>;
    errorMessage: ReturnType<typeof vi.fn>;
    markAllRead: ReturnType<typeof vi.fn>;
    markRead: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    localStorage.clear();
    document.documentElement.classList.remove('dark');
    mockAuthService = { logout: vi.fn(), getProfile: vi.fn().mockReturnValue(of({ accountId: 4 })) };
    mockNotificationsStore = {
      load: vi.fn(),
      watch: vi.fn().mockReturnValue(1),
      unwatch: vi.fn(),
      unreadCount: vi.fn().mockReturnValue(0),
      notifications: vi.fn().mockReturnValue([]),
      loaded: vi.fn().mockReturnValue(true),
      errorMessage: vi.fn().mockReturnValue(''),
      markAllRead: vi.fn(),
      markRead: vi.fn()
    };

    await TestBed.configureTestingModule({
      imports: [AppShell],
      providers: [
        { provide: AuthService, useValue: mockAuthService },
        { provide: NotificationsStore, useValue: mockNotificationsStore },
        { provide: NotificationsService, useValue: { detail: vi.fn().mockReturnValue(of({ notification: {}, order: null, alert: null })) } },
        // The assistant panel is mounted in the shell; these tests do not exercise it, so it gets no HTTP client.
        { provide: AssistantService, useValue: { ask: vi.fn() } },
        { provide: PreferencesService, useValue: { getPreferences: vi.fn().mockReturnValue(of({ avatarKey: 'avatar_01' })) } },
        provideRouter([])
      ]
    }).compileComponents();
  });

  function createShell(title = 'Blotter') {
    const fixture = TestBed.createComponent(AppShell);
    fixture.componentRef.setInput('title', title);
    fixture.detectChanges();
    return fixture;
  }

  it('shows the page title as the heading', () => {
    const fixture = createShell('Blotter');

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Blotter');
  });

  it('links to the dashboard, the order ticket and the blotter', () => {
    const fixture = createShell();

    for (const id of ['nav-dashboard', 'nav-trade', 'nav-blotter']) {
      expect(fixture.nativeElement.querySelector(`[data-testid="${id}"]`)).toBeTruthy();
    }
  });

  it('shows a top-bar notifications bell with an unread badge', () => {
    mockNotificationsStore.unreadCount.mockReturnValue(3);
    const fixture = createShell();

    expect(fixture.nativeElement.querySelector('[data-testid="nav-notifications"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[data-testid="nav-notifications-badge"]').textContent).toContain('3');
  });

  it('opens the notifications panel and marks all notifications read', () => {
    mockNotificationsStore.unreadCount.mockReturnValue(2);
    mockNotificationsStore.notifications.mockReturnValue([
      { subject: 'Order filled', message: 'Filled at 3425.00', channel: 'EMAIL', readOn: null, createdOn: '2026-10-01T00:00:00Z' }
    ]);
    const fixture = createShell();

    (fixture.nativeElement.querySelector('[data-testid="nav-notifications"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="notifications-panel"]')).toBeTruthy();

    (fixture.nativeElement.querySelector('[data-testid="notifications-mark-read"]') as HTMLButtonElement).click();
    expect(mockNotificationsStore.markAllRead).toHaveBeenCalledWith(4);
  });

  it('closes the notifications panel when clicking anywhere outside it', () => {
    mockNotificationsStore.unreadCount.mockReturnValue(1);
    const fixture = createShell();

    (fixture.nativeElement.querySelector('[data-testid="nav-notifications"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="notifications-panel"]')).toBeTruthy();

    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="notifications-panel"]')).toBeNull();
  });

  it('signing out clears the session and returns to sign-in', () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = createShell();

    (fixture.nativeElement.querySelector('[data-testid="account-menu"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[data-testid="signout"]') as HTMLButtonElement).click();

    expect(mockAuthService.logout).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith(['/sign-in']);
  });

  it('the theme toggle switches the page out of the dark default and back', () => {
    const fixture = createShell();
    const toggle = fixture.nativeElement.querySelector('[data-testid="theme-toggle"]') as HTMLButtonElement;

    // Dark is the platform default, so the first press is the one that gives a light page.
    toggle.click();
    expect(document.documentElement.classList.contains('dark')).toBe(false);

    toggle.click();
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });

  it('has a skip link that moves focus to the main content', () => {
    const fixture = createShell();
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('[data-testid="skip-link"]');
    const main: HTMLElement = fixture.nativeElement.querySelector('main#main-content');
    document.body.appendChild(fixture.nativeElement);

    link.click();

    expect(main).toBeTruthy();
    expect(document.activeElement).toBe(main);
    fixture.nativeElement.remove();
  });

  it('clicking a notification in the panel closes the panel and opens it in a dialog', () => {
    mockNotificationsStore.unreadCount.mockReturnValue(1);
    mockNotificationsStore.notifications.mockReturnValue([
      { id: 'n1', accountId: 4, type: 'ORDER_FILLED', channel: 'IN_APP', subject: 'Order filled: TCS.NS',
        message: 'filled', deliveryState: 'SENT', readOn: null, createdOn: '2026-10-01T00:00:00Z' }
    ]);
    const fixture = createShell();
    (fixture.nativeElement.querySelector('[data-testid="nav-notifications"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="notifications-open"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="notifications-panel"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="notification-detail"]')).toBeTruthy();
    expect(mockNotificationsStore.markRead).toHaveBeenCalledWith(4, 'n1', expect.any(Function));

    (fixture.nativeElement.querySelector('[data-testid="notification-detail-close"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="notification-detail"]')).toBeNull();
  });

  it('keeps the badge current while the page is open, and stops when the page goes', () => {
    const fixture = createShell();

    expect(mockNotificationsStore.watch).toHaveBeenCalledWith(4);
    fixture.destroy();
    expect(mockNotificationsStore.unwatch).toHaveBeenCalledWith(1);
  });

  it('opening the bell re-reads the inbox at once', () => {
    const fixture = createShell();
    (fixture.nativeElement.querySelector('[data-testid="nav-notifications"]') as HTMLButtonElement).click();

    expect(mockNotificationsStore.load).toHaveBeenCalledWith(4);
  });

  it('the panel lists each notification by subject only; the message is in the dialog', () => {
    mockNotificationsStore.notifications.mockReturnValue([
      { id: 'n1', accountId: 4, type: 'ORDER_FILLED', channel: 'IN_APP', subject: 'Order filled: TCS.NS',
        message: 'Your BUY order was filled at 3425.00', deliveryState: 'SENT', readOn: null, createdOn: '2026-10-01T00:00:00Z' }
    ]);
    const fixture = createShell();
    (fixture.nativeElement.querySelector('[data-testid="nav-notifications"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const item = fixture.nativeElement.querySelector('[data-testid="notifications-row"]').textContent;
    expect(item).toContain('Order filled: TCS.NS');
    expect(item).not.toContain('filled at 3425.00');
  });
});
