import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SessionStore } from '../auth/session.store';
import { authHeaderInterceptor } from './auth-header.interceptor';

describe('authHeaderInterceptor', () => {
  let sessionStore: SessionStore;
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let mockRouter: { navigate: ReturnType<typeof vi.fn>; url: string };

  beforeEach(() => {
    sessionStorage.clear();
    mockRouter = { navigate: vi.fn().mockResolvedValue(true), url: '/blotter' };

    TestBed.configureTestingModule({
      providers: [
        SessionStore,
        { provide: Router, useValue: mockRouter },
        provideHttpClient(withInterceptors([authHeaderInterceptor])),
        provideHttpClientTesting()
      ]
    });

    sessionStore = TestBed.inject(SessionStore);
    sessionStore.set({ accessToken: 'test-token' });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    sessionStorage.clear();
  });

  it('a request to your API carries the bearer token', () => {
    http.get('/api/v1/accounts/1').subscribe();

    const request = httpMock.expectOne('/api/v1/accounts/1');
    expect(request.request.headers.get('Authorization')).toBe('Bearer test-token');
    request.flush({});
  });

  it('a request to a third-party origin does not carry the bearer token', () => {
    http.get('https://example.com/metrics').subscribe();

    const request = httpMock.expectOne('https://example.com/metrics');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('the unauthenticated auth routes are called without a header', () => {
    for (const path of ['/auth/login', '/auth/register', '/auth/refresh']) {
      http.post(path, {}).subscribe();

      const request = httpMock.expectOne(path);
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush({});
    }
  });

  it('the protected auth route carries the bearer token', () => {
    http.get('/auth/me').subscribe();

    const request = httpMock.expectOne('/auth/me');
    expect(request.request.headers.get('Authorization')).toBe('Bearer test-token');
    request.flush({});
  });

  it('a same-origin path that is not a platform API path carries no token', () => {
    http.get('/assets/config.json').subscribe();

    const request = httpMock.expectOne('/assets/config.json');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('a third-party URL that copies our API path carries no token', () => {
    http.get('https://evil.example/api/v1/accounts/1').subscribe();

    const request = httpMock.expectOne('https://evil.example/api/v1/accounts/1');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('a protocol-relative URL to another host carries no token', () => {
    http.get('//evil.example/api/v1/accounts/1').subscribe();

    const request = httpMock.expectOne('//evil.example/api/v1/accounts/1');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('no header is added when the user is signed out', () => {
    sessionStore.clear();
    http.get('/api/v1/accounts/1').subscribe();

    const request = httpMock.expectOne('/api/v1/accounts/1');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('an absolute URL on our own origin carries the bearer token', () => {
    const url = `${globalThis.location.origin}/api/v1/accounts/1`;
    http.get(url).subscribe();

    const request = httpMock.expectOne(url);
    expect(request.request.headers.get('Authorization')).toBe('Bearer test-token');
    request.flush({});
  });

  it('a query string does not change the decision for a platform API call', () => {
    http.get('/api/v1/accounts/1/orders?status=NEW').subscribe();

    const request = httpMock.expectOne('/api/v1/accounts/1/orders?status=NEW');
    expect(request.request.headers.get('Authorization')).toBe('Bearer test-token');
    request.flush([]);
  });

  it('a trailing slash on a public auth route still carries no token', () => {
    for (const path of ['/auth/login/', '/auth/register/', '/auth/refresh/']) {
      http.post(path, {}).subscribe();

      const request = httpMock.expectOne(path);
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush({});
    }
  });

  it('a path that only looks like ours carries no token', () => {
    for (const path of ['/apiary/v1/accounts/1', '/api', '/auth/me/extra', '/auth/menu']) {
      http.get(path).subscribe();

      const request = httpMock.expectOne(path);
      expect(request.request.headers.has('Authorization'), `${path} must not carry the token`).toBe(false);
      request.flush({});
    }
  });

  it('a URL that uses our host name as user information for another host carries no token', () => {
    const url = `https://${globalThis.location.hostname}@evil.example/api/v1/accounts/1`;
    http.get(url).subscribe();

    const request = httpMock.expectOne(url);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('a third-party call made while signed in leaves the stored session untouched', () => {
    http.get('https://example.com/metrics').subscribe({ error: () => undefined });

    httpMock.expectOne('https://example.com/metrics').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(sessionStore.isAuthenticated()).toBe(true);
    expect(mockRouter.navigate).not.toHaveBeenCalled();
  });

  describe('an expired session (401 on a call that carried the token)', () => {
    it('clears the session and sends the user to sign-in carrying the page they were on', () => {
      let failure: unknown;
      http.get('/api/v1/accounts/1/orders').subscribe({ error: (error) => (failure = error) });

      httpMock.expectOne('/api/v1/accounts/1/orders').flush(
        { errorCode: 'AUTH-401', message: 'Unauthorised' },
        { status: 401, statusText: 'Unauthorized' }
      );

      expect(sessionStore.isAuthenticated()).toBe(false);
      expect(mockRouter.navigate).toHaveBeenCalledTimes(1);
      expect(mockRouter.navigate).toHaveBeenCalledWith(['/sign-in'], {
        queryParams: { returnUrl: '/blotter', reason: 'expired' }
      });
      expect(failure).toBeTruthy();
    });

    it('several calls failing at once cause a single redirect', () => {
      http.get('/api/v1/accounts/1/orders').subscribe({ error: () => undefined });
      http.get('/api/v1/accounts/1/balance').subscribe({ error: () => undefined });

      httpMock.expectOne('/api/v1/accounts/1/orders').flush({}, { status: 401, statusText: 'Unauthorized' });
      httpMock.expectOne('/api/v1/accounts/1/balance').flush({}, { status: 401, statusText: 'Unauthorized' });

      expect(mockRouter.navigate).toHaveBeenCalledTimes(1);
    });

    it('a refused sign-in (no token was sent) does not redirect', () => {
      sessionStore.clear();
      http.post('/auth/login', {}).subscribe({ error: () => undefined });

      httpMock.expectOne('/auth/login').flush(
        { errorCode: 'AUTH-401', message: 'Unauthorised' },
        { status: 401, statusText: 'Unauthorized' }
      );

      expect(mockRouter.navigate).not.toHaveBeenCalled();
    });

    it('a server error leaves the session alone', () => {
      http.get('/api/v1/accounts/1/orders').subscribe({ error: () => undefined });

      httpMock.expectOne('/api/v1/accounts/1/orders').flush({}, { status: 500, statusText: 'Server Error' });

      expect(sessionStore.isAuthenticated()).toBe(true);
      expect(mockRouter.navigate).not.toHaveBeenCalled();
    });
  });
});
