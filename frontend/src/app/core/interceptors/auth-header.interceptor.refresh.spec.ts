import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { authHeaderInterceptor } from './auth-header.interceptor';

const unauthorised = { status: 401, statusText: 'Unauthorized' };

/**
 * An expired access token with a refresh token still valid: the call is repeated with a renewed token
 * and the user never notices. Refresh tokens work once, so the renewal is shared and tried once.
 */
describe('authHeaderInterceptor: silent renewal', () => {
  let session: SessionStore;
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: { navigate: ReturnType<typeof vi.fn>; url: string };
  let auth: { refreshShared: ReturnType<typeof vi.fn> };

  /** What the real AuthService does on success: store the new tokens. */
  const renewsTo = (accessToken: string, refreshToken = 'r2') =>
    auth.refreshShared.mockImplementation(() => {
      session.set({ accessToken, refreshToken });
      return of({ accessToken, refreshToken });
    });

  beforeEach(() => {
    sessionStorage.clear();
    router = { navigate: vi.fn().mockResolvedValue(true), url: '/trade' };
    auth = { refreshShared: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        SessionStore,
        { provide: Router, useValue: router },
        { provide: AuthService, useValue: auth },
        provideHttpClient(withInterceptors([authHeaderInterceptor])),
        provideHttpClientTesting()
      ]
    });
    session = TestBed.inject(SessionStore);
    session.set({ accessToken: 'old', refreshToken: 'r1' });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    sessionStorage.clear();
  });

  it('renews the token and repeats the call with it, without ending the session', () => {
    renewsTo('new');
    let body: unknown;
    http.post('/api/v1/orders', { x: 1 }).subscribe((b) => (body = b));

    httpMock.expectOne('/api/v1/orders').flush({}, unauthorised);
    const retry = httpMock.expectOne('/api/v1/orders');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new');
    expect(retry.request.body).toEqual({ x: 1 });
    retry.flush({ ok: true });

    expect(body).toEqual({ ok: true });
    expect(session.isAuthenticated()).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('calls that fail together are all repeated once the shared renewal arrives', () => {
    const renewal = new Subject<any>();
    auth.refreshShared.mockReturnValue(renewal);
    const results: unknown[] = [];
    http.get('/api/v1/a').subscribe((b) => results.push(b));
    http.get('/api/v1/b').subscribe((b) => results.push(b));

    httpMock.expectOne('/api/v1/a').flush({}, unauthorised);
    httpMock.expectOne('/api/v1/b').flush({}, unauthorised);
    session.set({ accessToken: 'new', refreshToken: 'r2' });
    renewal.next({ accessToken: 'new', refreshToken: 'r2' });
    renewal.complete();
    httpMock.expectOne('/api/v1/a').flush('A');
    httpMock.expectOne('/api/v1/b').flush('B');

    expect(results).toEqual(['A', 'B']);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('a call refused with a token that another call has already replaced uses the new token without refreshing again', () => {
    http.get('/api/v1/a').subscribe();
    const first = httpMock.expectOne('/api/v1/a');
    session.set({ accessToken: 'already-new', refreshToken: 'r2' });

    first.flush({}, unauthorised);

    const retry = httpMock.expectOne('/api/v1/a');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer already-new');
    expect(auth.refreshShared).not.toHaveBeenCalled();
    retry.flush({});
  });

  it('ends the session when the refresh token is refused', () => {
    auth.refreshShared.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));
    let failure: unknown;
    http.get('/api/v1/a').subscribe({ error: (e) => (failure = e) });

    httpMock.expectOne('/api/v1/a').flush({}, unauthorised);

    expect(failure).toBeTruthy();
    expect(session.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/sign-in'], { queryParams: { returnUrl: '/trade', reason: 'expired' } });
  });

  it('keeps the session when the refresh failed only because the network or server is down', () => {
    auth.refreshShared.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    let failure: unknown;
    http.get('/api/v1/a').subscribe({ error: (e) => (failure = e) });

    httpMock.expectOne('/api/v1/a').flush({}, unauthorised);

    expect(failure).toBeTruthy();
    expect(session.isAuthenticated()).toBe(true);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('ends the session when the repeated call is refused too, and does not loop', () => {
    renewsTo('new');
    http.get('/api/v1/a').subscribe({ error: () => undefined });

    httpMock.expectOne('/api/v1/a').flush({}, unauthorised);
    httpMock.expectOne('/api/v1/a').flush({}, unauthorised);

    expect(auth.refreshShared).toHaveBeenCalledTimes(1);
    expect(session.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledTimes(1);
  });

  it('with no refresh token the session ends at once, as before', () => {
    session.clear();
    session.set({ accessToken: 'old' });
    http.get('/api/v1/a').subscribe({ error: () => undefined });

    httpMock.expectOne('/api/v1/a').flush({}, unauthorised);

    expect(auth.refreshShared).not.toHaveBeenCalled();
    expect(session.isAuthenticated()).toBe(false);
  });
});
