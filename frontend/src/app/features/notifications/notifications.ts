import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

import { AuthService } from '../../core/auth/auth.service';
import { NotificationsStore } from '../../core/notifications/notifications.store';
import { messageFor } from '../../core/errors/error-messages';
import { AppShell } from '../../shared/layout/app-shell';
import { NotificationDetail } from '../../shared/notifications/notification-detail';
import { NotificationResponse } from '../../clients/notifications-client/model/models';

/**
 * The customer inbox (Sprint 10). Every order outcome and triggered alert appears here, newest first,
 * showing the channel it was routed on. Opening one shows it in full, with the order or alert it is
 * about, and marks just that one read; "Mark all read" clears the lot. Both change the sidebar badge
 * too, because everything reads the same store.
 */
@Component({
  selector: 'app-notifications',
  imports: [CommonModule, AppShell, NotificationDetail],
  template: `
    <app-shell title="Notifications">
      <div class="lt-card lt-card-pad lt-appear">
        <div class="lt-card-head">
          <p class="text-[13px]" style="color: var(--muted)">Open a notification to see the full message and the order or alert it is about. The same inbox is under the bell in the top bar.</p>
          <button
            type="button"
            class="lt-btn lt-btn-ghost lt-btn-sm"
            data-testid="notifications-mark-read"
            [disabled]="store.unreadCount() === 0"
            (click)="markAllRead()"
          >Mark all read</button>
        </div>

        <div>
        <div class="lt-alert lt-alert-down mb-3"
          data-testid="notifications-error" role="alert" *ngIf="errorMessage() || store.errorMessage()">{{ errorMessage() || store.errorMessage() }}</div>

        <div class="lt-empty" data-testid="notifications-loading"
          *ngIf="!store.loaded() && !errorMessage() && !store.errorMessage()">
          <p>Loading your notifications...</p>
        </div>

        <div class="lt-empty" data-testid="notifications-empty"
          *ngIf="store.loaded() && store.notifications().length === 0 && !errorMessage() && !store.errorMessage()">
          <p>No notifications yet.</p>
        </div>

        <ul class="grid gap-1.5" *ngIf="store.notifications().length > 0">
          <li *ngFor="let n of store.notifications(); trackBy: trackById" data-testid="notifications-row">
            <!-- The whole item is one button: it opens the notification in a dialog. -->
            <button type="button" data-testid="notifications-open"
              class="lt-notifications__open rounded-lg border px-3.5 py-2.5"
              [style.border-color]="n.readOn ? 'var(--border)' : 'var(--accent)'"
              [style.background]="n.readOn ? 'var(--surface)' : 'var(--accent-soft)'"
              (click)="open(n)">
              <span class="flex items-start justify-between gap-3">
                <span class="block">
                  <span class="block text-[14px] font-bold" style="color: var(--text)">{{ n.subject }}</span>
                </span>
                <span class="inline-flex shrink-0 items-center gap-2">
                  <span class="lt-badge lt-badge-info" data-testid="notifications-channel">{{ n.channel }}</span>
                  <span *ngIf="!n.readOn" class="h-2 w-2 rounded-full" style="background: var(--accent)"
                    data-testid="notifications-unread-dot" aria-hidden="true"></span>
                </span>
              </span>
              <span class="mt-1 flex items-center justify-between gap-3 text-[11.5px]" style="color: var(--muted)">
                <span>{{ n.createdOn | date:'medium' }}</span>
                <span>{{ n.readOn ? 'Read' : 'Unread' }}</span>
              </span>
            </button>
          </li>
        </ul>
        </div>
      </div>

      <app-notification-detail *ngIf="opened() as o" [notification]="o" [accountId]="accountId()!"
        (closed)="opened.set(null)"></app-notification-detail>
    </app-shell>
  `
})
export class Notifications implements OnInit {
  private readonly auth = inject(AuthService);
  protected readonly store = inject(NotificationsStore);

  protected readonly accountId = signal<number | null>(null);
  readonly errorMessage = signal('');
  /** The notification open in the detail dialog, if any. */
  readonly opened = signal<NotificationResponse | null>(null);

  ngOnInit(): void {
    this.auth.getProfile().subscribe({
      next: (profile) => {
        this.accountId.set(profile.accountId);
        this.store.load(profile.accountId);
      },
      error: (err) => this.errorMessage.set(messageFor(err))
    });
  }

  open(notification: NotificationResponse): void {
    if (this.accountId() !== null) {
      this.opened.set(notification);
    }
  }

  protected trackById(_: number, notification: NotificationResponse): string {
    return notification.id;
  }

  markAllRead(): void {
    const accountId = this.accountId();
    if (accountId !== null) {
      this.store.markAllRead(accountId);
    }
  }
}
