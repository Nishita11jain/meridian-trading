import { TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService as GeneratedAuthService } from '../../clients/auth-client/api/auth.service';
import { ProfileService as GeneratedProfileService } from '../../clients/auth-client/api/profile.service';
import { AuthService } from './auth.service';
import { SessionStore } from './session.store';

describe('AuthService.refreshShared', () => {
  let client: { refresh: ReturnType<typeof vi.fn> };
  let service: AuthService;
  let session: SessionStore;

  beforeEach(() => {
    sessionStorage.clear();
    client = { refresh: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: GeneratedAuthService, useValue: client },
        { provide: GeneratedProfileService, useValue: {} }
      ]
    });
    service = TestBed.inject(AuthService);
    session = TestBed.inject(SessionStore);
    session.set({ accessToken: 'old', refreshToken: 'r1' });
  });

  it('callers at the same time share one request, because a refresh token works once', () => {
    const answer = new Subject<any>();
    client.refresh.mockReturnValue(answer);
    const results: string[] = [];

    service.refreshShared().subscribe((t) => results.push(t.accessToken));
    service.refreshShared().subscribe((t) => results.push(t.accessToken));
    answer.next({ accessToken: 'new', refreshToken: 'r2' });
    answer.complete();

    expect(client.refresh).toHaveBeenCalledTimes(1);
    expect(results).toEqual(['new', 'new']);
    expect(session.accessToken()).toBe('new');
    expect(session.refreshToken()).toBe('r2');
  });

  it('a later refresh makes a new request, with the rotated token', () => {
    client.refresh
      .mockReturnValueOnce(of({ accessToken: 'a2', refreshToken: 'r2' }))
      .mockReturnValueOnce(of({ accessToken: 'a3', refreshToken: 'r3' }));

    service.refreshShared().subscribe();
    service.refreshShared().subscribe();

    expect(client.refresh).toHaveBeenCalledTimes(2);
    expect(client.refresh).toHaveBeenLastCalledWith({ refreshToken: 'r2' });
  });

  it('fails without calling the API when there is no refresh token', () => {
    session.clear();
    let failure: unknown;

    service.refreshShared().subscribe({ error: (e) => (failure = e) });

    expect(failure).toBeTruthy();
    expect(client.refresh).not.toHaveBeenCalled();
  });
});
