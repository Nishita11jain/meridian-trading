import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// Wraps the generated Notifications client, like the other core services wrap their clients.
import { NotificationsService as GeneratedNotificationsService } from '../../clients/notifications-client/api/notifications.service';
import { NotificationDetailResponse, NotificationListResponse } from '../../clients/notifications-client/model/models';

@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly client = inject(GeneratedNotificationsService);

  list(accountId: number): Observable<NotificationListResponse> {
    return this.client.getNotifications(accountId);
  }

  markAllRead(accountId: number): Observable<NotificationListResponse> {
    return this.client.markNotificationsRead(accountId);
  }

  /** One notification with the order or alert it is about. Read-only: it does not mark it read. */
  detail(accountId: number, notificationId: string): Observable<NotificationDetailResponse> {
    return this.client.getNotification(accountId, notificationId);
  }

  markRead(accountId: number, notificationId: string): Observable<NotificationListResponse> {
    return this.client.markNotificationRead(accountId, notificationId);
  }

  markUnread(accountId: number, notificationId: string): Observable<NotificationListResponse> {
    return this.client.markNotificationUnread(accountId, notificationId);
  }
}
