import { Injectable, inject } from '@angular/core';
import { Observable, finalize, shareReplay, tap, throwError } from 'rxjs';

// Import from generated client modules (not the barrel)
import { AuthService as GeneratedAuthService } from '../../clients/auth-client/api/auth.service';
import { ProfileService as GeneratedProfileService } from '../../clients/auth-client/api/profile.service';
import {
  LoginRequest,
  RegisterRequest,
  TokenResponse,
  UserResponse,
  RefreshRequest
} from '../../clients/auth-client/model/models';
import { SessionStore } from './session.store';

/**
 * Wrapper service around the generated Auth API client.
 * 
 * Responsibilities:
 * - Manage token lifecycle via SessionStore (signals)
 * - Provide login/register/refresh/profile methods
 * - Simplify the generated client interface for app components
 * 
 * Generated client is in src/app/clients/auth-client; this service wraps it.
 * Do not edit the generated client. Add app-specific logic here.
 * 
 * Token state is stored in SessionStore (signals) and sessionStorage.
 * SessionStore is separate from this service to avoid circular dependency risk with interceptor (SEC2-689).
 */
@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly sessionStore = inject(SessionStore);
  private readonly generatedAuthClient = inject(GeneratedAuthService);
  private readonly generatedProfileClient = inject(GeneratedProfileService);

  /**
   * Public signal: true if authenticated (has access token).
   * Components use this to show/hide auth-dependent content.
   */
  readonly isAuthenticated = this.sessionStore.isAuthenticated;

  /**
   * Public signal: current access token (empty string if not authenticated).
   */
  readonly accessToken = this.sessionStore.accessToken;

  /**
   * Sign in with username and password.
   * Stores tokens in SessionStore (sessionStorage).
   * 
   * @param username Trading account username
   * @param password Account password
   * @returns Observable<TokenResponse> containing accessToken and refreshToken
   */
  login(username: string, password: string): Observable<TokenResponse> {
    const request: LoginRequest = { username, password };
    return this.generatedAuthClient.login(request).pipe(
      tap((response: TokenResponse) => {
        this.sessionStore.set({
          accessToken: response.accessToken,
          refreshToken: response.refreshToken
        });
      })
    );
  }

  /**
   * Register a new user linked to an existing trading account.
   * Does not return a session; caller must sign in afterwards.
   * 
   * @param username Desired username
   * @param password Account password
   * @param accountId Numeric trading account ID (must exist)
   * @returns Observable<UserResponse> with sub, accountId, roles
   */
  register(username: string, password: string, accountId: number): Observable<UserResponse> {
    const request: RegisterRequest = { username, password, accountId };
    return this.generatedAuthClient.register(request);
  }

  /**
   * Refresh the access token using the stored refresh token.
   * Called by interceptor when access token expires (or eagerly to rotate).
   * 
   * @returns Observable<TokenResponse> containing new accessToken and refreshToken
   */
  refresh(): Observable<TokenResponse> {
    const refreshToken = this.sessionStore.refreshToken();
    if (!refreshToken) {
      return throwError(() => new Error('No refresh token available'));
    }
    const request: RefreshRequest = { refreshToken };
    return this.generatedAuthClient.refresh(request).pipe(
      tap((response: TokenResponse) => {
        this.sessionStore.set({
          accessToken: response.accessToken,
          refreshToken: response.refreshToken
        });
      })
    );
  }

  private refreshInFlight: Observable<TokenResponse> | null = null;

  /**
   * Refresh, but at most one request at a time: callers that arrive while one is running share its
   * answer. Refresh tokens are single-use, so two parallel refreshes would make the second one fail
   * and sign the user out for no reason.
   */
  refreshShared(): Observable<TokenResponse> {
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.refresh().pipe(
        finalize(() => (this.refreshInFlight = null)),
        shareReplay({ bufferSize: 1, refCount: false })
      );
    }
    return this.refreshInFlight;
  }

  /**
   * Get the current user profile (protected route requiring valid token).
   * 
   * @returns Observable<UserResponse> with sub, accountId, roles
   */
  getProfile(): Observable<UserResponse> {
    return this.generatedProfileClient.getCurrentUser();
  }

  /**
   * Sign out: clear tokens from SessionStore.
   */
  logout(): void {
    this.sessionStore.clear();
  }
}

