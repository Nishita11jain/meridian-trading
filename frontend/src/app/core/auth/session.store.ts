import { computed, signal, Injectable } from '@angular/core';

// SEC2-688: Session store holds access and refresh tokens using signals
// Tokens are stored in sessionStorage (not localStorage, so token doesn't outlive the tab)
// This store is separate from AuthService to avoid circular dependency risk with interceptor (SEC2-689)

export interface SessionTokens {
  accessToken: string;
  refreshToken?: string;
}

@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly accessTokenSignal = signal<string>('');
  private readonly refreshTokenSignal = signal<string>('');

  // Public read-only signals
  readonly accessToken = this.accessTokenSignal.asReadonly();
  readonly refreshToken = this.refreshTokenSignal.asReadonly();

  // Computed: true if there's an access token
  readonly isAuthenticated = computed(() => this.accessTokenSignal() !== '');

  constructor() {
    this.loadFromSessionStorage();
  }

  /**
   * Store tokens in memory signals and sessionStorage
   */
  set(tokens: SessionTokens): void {
    this.accessTokenSignal.set(tokens.accessToken);
    if (tokens.refreshToken) {
      this.refreshTokenSignal.set(tokens.refreshToken);
    }
    this.saveToSessionStorage();
  }

  /**
   * Clear all tokens from memory signals and sessionStorage
   */
  clear(): void {
    this.accessTokenSignal.set('');
    this.refreshTokenSignal.set('');
    this.clearSessionStorage();
  }

  /**
   * Load tokens from sessionStorage on initialization
   */
  private loadFromSessionStorage(): void {
    try {
      const stored = sessionStorage.getItem('tokens');
      if (stored) {
        const tokens = JSON.parse(stored) as SessionTokens;
        this.accessTokenSignal.set(tokens.accessToken || '');
        this.refreshTokenSignal.set(tokens.refreshToken || '');
      }
    } catch {
      // Silently ignore storage errors; continue with empty tokens
    }
  }

  /**
   * Save current tokens to sessionStorage
   */
  private saveToSessionStorage(): void {
    try {
      const tokens: SessionTokens = {
        accessToken: this.accessTokenSignal(),
        refreshToken: this.refreshTokenSignal()
      };
      sessionStorage.setItem('tokens', JSON.stringify(tokens));
    } catch {
      // Silently ignore storage errors; tokens remain in memory
    }
  }

  /**
   * Clear sessionStorage
   */
  private clearSessionStorage(): void {
    try {
      sessionStorage.removeItem('tokens');
    } catch {
      // Silently ignore storage errors
    }
  }
}
