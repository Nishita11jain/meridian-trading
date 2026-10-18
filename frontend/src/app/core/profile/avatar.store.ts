import { Injectable, computed, signal } from '@angular/core';

/** The predefined avatars. Each key is one the server accepts; the colours are the only thing the frontend owns. */
export const AVATARS: readonly { key: string; label: string; color: string }[] = [
  // Muted mid-tones that sit with the lilac/amber theme on both light and dark; white initials read on each.
  { key: 'avatar_01', label: 'Teal', color: '#3F8F84' },
  { key: 'avatar_02', label: 'Blue', color: '#3E86AE' },
  { key: 'avatar_03', label: 'Violet', color: '#7462D6' },
  { key: 'avatar_04', label: 'Rose', color: '#B85C8A' },
  { key: 'avatar_05', label: 'Amber', color: '#C07B3A' },
  { key: 'avatar_06', label: 'Green', color: '#4E9670' },
  { key: 'avatar_07', label: 'Slate', color: '#626A85' },
  { key: 'avatar_08', label: 'Indigo', color: '#5A63C4' }
];

/**
 * The avatar the user has saved. The top bar and the profile page both read it, so they always agree.
 * It changes only when the server has accepted a save, never when the user merely picks a swatch.
 */
@Injectable({ providedIn: 'root' })
export class AvatarStore {
  readonly savedKey = signal(AVATARS[0].key);
  readonly savedColor = computed(() => AVATARS.find((a) => a.key === this.savedKey())?.color ?? AVATARS[0].color);

  set(key: string): void {
    if (AVATARS.some((a) => a.key === key)) {
      this.savedKey.set(key);
    }
  }
}
