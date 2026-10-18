import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// Wraps the generated Preferences client, like core/orders/order.service.ts wraps the trade client.
// Do not edit the generated client; app-specific shaping lives here.
import { PreferencesService as GeneratedPreferencesService } from '../../clients/preferences-client/api/preferences.service';
import {
  PreferenceResponse,
  UpdateNotificationPreferenceRequest,
  UpdateDisplayPreferenceRequest
} from '../../clients/preferences-client/model/models';

@Injectable({ providedIn: 'root' })
export class PreferencesService {
  private readonly client = inject(GeneratedPreferencesService);

  getPreferences(accountId: number): Observable<PreferenceResponse> {
    return this.client.getPreferences(accountId);
  }

  updateNotification(accountId: number, request: UpdateNotificationPreferenceRequest): Observable<PreferenceResponse> {
    return this.client.updateNotificationPreference(accountId, request);
  }

  updateDisplay(accountId: number, request: UpdateDisplayPreferenceRequest): Observable<PreferenceResponse> {
    return this.client.updateDisplayPreference(accountId, request);
  }

  updateAvatar(accountId: number, avatarKey: string): Observable<PreferenceResponse> {
    return this.client.updateAvatarPreference(accountId, { avatarKey: avatarKey as never });
  }
}
