import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { SignIn } from './sign-in';
import { AuthService } from '../../core/auth/auth.service';
import { PreferencesService } from '../../core/preferences/preferences.service';
import { SessionStore } from '../../core/auth/session.store';
import { of, throwError, Subject } from 'rxjs';
import { ReactiveFormsModule } from '@angular/forms';

describe('SignIn', () => {
  let component: SignIn;
  let mockAuthService: any;
  let mockPreferencesService: any;
  let mockSessionStore: any;
  let mockRouter: any;
  let mockActivatedRoute: any;

  beforeEach(async () => {
    sessionStorage.clear();

    // Mock dependencies
    mockAuthService = {
      login: vi.fn()
    };

    mockPreferencesService = {
      getPreferences: vi.fn()
    };

    mockSessionStore = {
      accessToken: vi.fn(() => ''),
      set: vi.fn(),
      clear: vi.fn()
    };

    mockRouter = {
      navigate: vi.fn(),
      navigateByUrl: vi.fn()
    };

    mockActivatedRoute = {
      snapshot: {
        queryParamMap: convertToParamMap({})
      }
    };

    // Set up TestBed
    await TestBed.configureTestingModule({
      imports: [SignIn, ReactiveFormsModule],
      providers: [
        { provide: AuthService, useValue: mockAuthService },
        { provide: PreferencesService, useValue: mockPreferencesService },
        { provide: SessionStore, useValue: mockSessionStore },
        { provide: Router, useValue: mockRouter },
        { provide: ActivatedRoute, useValue: mockActivatedRoute }
      ]
    }).compileComponents();

    // Create component
    const fixture = TestBed.createComponent(SignIn);
    component = fixture.componentInstance;
  });

  describe('form validation', () => {
    it('should require username', () => {
      // Arrange
      component.form.patchValue({ username: '', password: 'password' });

      // Assert
      expect(component.form.get('username')?.invalid).toBe(true);
      expect(component.form.invalid).toBe(true);
    });

    it('should require password', () => {
      // Arrange
      component.form.patchValue({ username: 'user', password: '' });

      // Assert
      expect(component.form.get('password')?.invalid).toBe(true);
      expect(component.form.invalid).toBe(true);
    });

    it('should validate username maxLength 64', () => {
      // Arrange
      const longUsername = 'a'.repeat(65);
      component.form.patchValue({ username: longUsername, password: 'pass' });

      // Assert
      expect(component.form.get('username')?.invalid).toBe(true);
    });

    it('should validate password maxLength 128', () => {
      // Arrange
      const longPassword = 'a'.repeat(129);
      component.form.patchValue({ username: 'user', password: longPassword });

      // Assert
      expect(component.form.get('password')?.invalid).toBe(true);
    });

    it('should be valid with required fields', () => {
      // Arrange
      component.form.patchValue({ username: 'testuser', password: 'testpass' });

      // Assert
      expect(component.form.valid).toBe(true);
    });
  });

  describe('onSubmit()', () => {
    it('should not call login if form is invalid', () => {
      // Arrange
      component.form.patchValue({ username: '', password: 'pass' });

      // Act
      component.onSubmit();

      // Assert
      expect(mockAuthService.login).not.toHaveBeenCalled();
    });

    it('should mark all fields as touched if form is invalid', () => {
      // Arrange
      component.form.patchValue({ username: '', password: '' });

      // Act
      component.onSubmit();

      // Assert
      expect(component.form.get('username')?.touched).toBe(true);
      expect(component.form.get('password')?.touched).toBe(true);
    });

    it('should show generic error message if form is invalid', () => {
      // Arrange
      component.form.patchValue({ username: '', password: '' });

      // Act
      component.onSubmit();

      // Assert
      expect(component.errorMessage()).toBe('Enter your username and password');
    });

    it('should call login with valid credentials', () => {
      // Arrange
      component.form.patchValue({ username: 'user', password: 'pass' });
      mockAuthService.login.mockReturnValue(of({ accessToken: 'token' }));

      // Act
      component.onSubmit();

      // Assert
      expect(mockAuthService.login).toHaveBeenCalledWith('user', 'pass');
    });

    it('should set submitting signal while login is in progress', () => {
      // Arrange
      component.form.patchValue({ username: 'user', password: 'pass' });
      const loginSubject = new Subject();
      mockAuthService.login.mockReturnValue(loginSubject);
      
      expect(component.submitting()).toBe(false);

      // Act: submit (but don't emit from the subject yet)
      component.onSubmit();

      // Assert: submitting should be true while the Observable is pending
      expect(component.submitting()).toBe(true);
      expect(mockAuthService.login).toHaveBeenCalled();

      // Act: emit the successful response
      loginSubject.next({ accessToken: 'token' });

      // Assert: submitting should be false after the Observable completes
      expect(component.submitting()).toBe(false);
    });

    it('should navigate to dashboard on successful login', () => {
      // Arrange
      component.form.patchValue({ username: 'user', password: 'pass' });
      mockAuthService.login.mockReturnValue(of({ accessToken: 'token' }));

      // Act
      component.onSubmit();

      // Assert
      expect(mockRouter.navigateByUrl).toHaveBeenCalledWith('/dashboard', { replaceUrl: true });
    });

    it('does not copy the stored default account or currency into sessionStorage', () => {
      // Arrange
      const payload = btoa(JSON.stringify({ accountId: 4 }));
      const token = `h.${payload}.s`;
      mockSessionStore.accessToken.mockReturnValue(token);
      mockAuthService.login.mockReturnValue(of({ accessToken: token }));
      mockPreferencesService.getPreferences.mockReturnValue(of({
        accountId: 4,
        defaultAccountId: 9,
        notificationChannel: 'IN_APP',
        contactEmail: null,
        contactPhone: null,
        displayCurrency: 'USD',
        theme: 'DARK',
        avatarKey: 'avatar_02',
        updatedOn: null
      }));
      component.form.patchValue({ username: 'user', password: 'pass' });

      // Act
      component.onSubmit();

      // Assert
      expect(mockPreferencesService.getPreferences).toHaveBeenCalledWith(4);
      expect(sessionStorage.getItem('defaultAccount')).toBeNull();
      expect(sessionStorage.getItem('preferredCurrency')).toBeNull();
    });

    it('should navigate to same-origin path return address after login', () => {
      // Arrange
      mockActivatedRoute.snapshot.queryParamMap = convertToParamMap({
        returnUrl: '/dashboard?tab=orders'
      });
      component.form.patchValue({ username: 'user', password: 'pass' });
      mockAuthService.login.mockReturnValue(of({ accessToken: 'token' }));

      // Act
      component.onSubmit();

      // Assert
      expect(mockRouter.navigateByUrl).toHaveBeenCalledWith('/dashboard?tab=orders', { replaceUrl: true });
    });

    it('tells the user their session expired when sent here by the interceptor', () => {
      mockActivatedRoute.snapshot.queryParamMap = convertToParamMap({ reason: 'expired', returnUrl: '/blotter' });

      const fixture = TestBed.createComponent(SignIn);

      expect(fixture.componentInstance.errorMessage()).toBe('Your session has expired. Please sign in again.');
    });

    it('replaces the sign-in entry in the browser history, so Back does not return to the form', () => {
      component.form.patchValue({ username: 'user', password: 'pass' });
      mockAuthService.login.mockReturnValue(of({ accessToken: 'token' }));

      component.onSubmit();

      expect(mockRouter.navigateByUrl).toHaveBeenCalledTimes(1);
      expect(mockRouter.navigateByUrl.mock.calls[0][1]).toEqual({ replaceUrl: true });
    });

    it('shows no message on a normal visit to sign-in', () => {
      const fixture = TestBed.createComponent(SignIn);

      expect(fixture.componentInstance.errorMessage()).toBe('');
    });

    describe('the return address is followed only when it is a path on this origin', () => {
      const signInWith = (returnUrl: string) => {
        mockActivatedRoute.snapshot.queryParamMap = convertToParamMap({ returnUrl });
        component.form.patchValue({ username: 'user', password: 'pass' });
        mockAuthService.login.mockReturnValue(of({ accessToken: 'token' }));
        component.onSubmit();
      };

      it.each([
        ['a protocol-relative URL to another host', '//evil.example/phish'],
        ['a javascript: URL', 'javascript:alert(1)'],
        ['a backslash that browsers read as a slash', '/\\evil.example'],
        ['a data: URL', 'data:text/html,<script>alert(1)</script>'],
        ['another host that starts with our host name', 'https://localhost.evil.example/x'],
        ['another host hidden behind user information', 'https://localhost@evil.example/x'],
        ['an https URL on another host', 'https://evil.example/phish']
      ])('refuses %s and lands on the dashboard', (_label, returnUrl) => {
        signInWith(returnUrl);

        expect(mockRouter.navigateByUrl).toHaveBeenCalledTimes(1);
        expect(mockRouter.navigateByUrl).toHaveBeenCalledWith('/dashboard', { replaceUrl: true });
      });

      it('lands on the dashboard when there is no return address', () => {
        component.form.patchValue({ username: 'user', password: 'pass' });
        mockAuthService.login.mockReturnValue(of({ accessToken: 'token' }));

        component.onSubmit();

        expect(mockRouter.navigateByUrl).toHaveBeenCalledWith('/dashboard', { replaceUrl: true });
      });

      it('lands on the dashboard when the return address is empty', () => {
        signInWith('');

        expect(mockRouter.navigateByUrl).toHaveBeenCalledWith('/dashboard', { replaceUrl: true });
      });

      it.each([
        ['a plain path', '/trade', '/trade'],
        ['a path with a query string and a fragment', '/blotter?tab=1#top', '/blotter?tab=1#top']
      ])('follows %s on this origin', (_label, returnUrl, expected) => {
        signInWith(returnUrl);

        expect(mockRouter.navigateByUrl).toHaveBeenCalledWith(expected, { replaceUrl: true });
      });

      it('follows an absolute URL on this origin, but only as a path', () => {
        signInWith(`${globalThis.location.origin}/blotter?tab=1`);

        expect(mockRouter.navigateByUrl).toHaveBeenCalledWith('/blotter?tab=1', { replaceUrl: true });
      });
    });

    it('an off-origin return address is refused', () => {
      // Arrange
      mockActivatedRoute.snapshot.queryParamMap = convertToParamMap({
        returnUrl: 'https://evil.example/phish'
      });
      component.form.patchValue({ username: 'user', password: 'pass' });
      mockAuthService.login.mockReturnValue(of({ accessToken: 'token' }));

      // Act
      component.onSubmit();

      // Assert
      expect(mockRouter.navigateByUrl).toHaveBeenCalledWith('/dashboard', { replaceUrl: true });
    });

    it('should reset form on successful login', () => {
      // Arrange
      component.form.patchValue({ username: 'user', password: 'pass' });
      mockAuthService.login.mockReturnValue(of({ accessToken: 'token' }));

      // Act
      component.onSubmit();

      // Assert
      expect(component.form.get('username')?.value).toBe(null);
      expect(component.form.get('password')?.value).toBe(null);
    });

    it('should show error message on 401 Unauthorized', () => {
      // Arrange
      component.form.patchValue({ username: 'user', password: 'wrong' });
      const error = new HttpErrorResponse({
        status: 401,
        error: { errorCode: 'AUTH-401', message: 'Invalid credentials' }
      });
      mockAuthService.login.mockReturnValue(throwError(() => error));

      // Act
      component.onSubmit();

      // Assert: errorMessage is set via messageFor() with AUTH-401 sentence
      expect(component.errorMessage()).toContain('Sign-in was refused');
    });

    it('should clear password field on error', async () => {
      // Arrange
      component.form.patchValue({ username: 'user', password: 'wrong' });
      const error = { errorCode: 'AUTH-401', message: 'Invalid' };
      mockAuthService.login.mockReturnValue(throwError(() => error));

      // Act
      component.onSubmit();

      // Wait for async operations to complete
      await new Promise<void>((resolve) => {
        setTimeout(() => {
          // Assert
          expect(component.form.get('password')?.value).toBe('');
          resolve();
        }, 10);
      });
    });

    it('should focus password field after failed sign-in', () => {
      // Arrange
      component.form.patchValue({ username: 'user', password: 'wrong' });
      const error = new HttpErrorResponse({
        status: 401,
        error: { errorCode: 'AUTH-401', message: 'Invalid' }
      });
      mockAuthService.login.mockReturnValue(throwError(() => error));

      // Set up spy on the password input focus method
      // We need to spy on the actual element after the component fixture is set up
      const fixture = TestBed.createComponent(SignIn);
      const componentInstance = fixture.componentInstance;
      fixture.detectChanges();

      // Ensure the view is initialized
      fixture.detectChanges();
      const passwordElement = fixture.nativeElement.querySelector('[data-testid="signin-password"]');
      const focusSpy = vi.spyOn(passwordElement, 'focus');

      // Act: trigger the error path
      componentInstance.form.patchValue({ username: 'user', password: 'wrong' });
      componentInstance.onSubmit();

      // Wait for the async focus operation
      return new Promise<void>((resolve) => {
        setTimeout(() => {
          // Assert: the password input should have been focused
          expect(focusSpy).toHaveBeenCalled();
          resolve();
        }, 100);
      });
    });

    it('should handle network errors (status 0)', () => {
      // Arrange
      component.form.patchValue({ username: 'user', password: 'pass' });
      const error = new HttpErrorResponse({
        status: 0,
        error: null
      });
      mockAuthService.login.mockReturnValue(throwError(() => error));

      // Act
      component.onSubmit();

      // Assert
      expect(component.errorMessage()).toContain('could not reach the service');
    });

    it('should set submitting to false on error', async () => {
      // Arrange
      component.form.patchValue({ username: 'user', password: 'pass' });
      const error = { errorCode: 'AUTH-401' };
      mockAuthService.login.mockReturnValue(throwError(() => error));

      // Act
      component.onSubmit();

      // Wait for async subscription to complete
      await new Promise<void>((resolve) => {
        setTimeout(() => {
          // Assert
          expect(component.submitting()).toBe(false);
          resolve();
        }, 10);
      });
    });
  });

  describe('signals', () => {
    it('should initialize submitting as false', () => {
      expect(component.submitting()).toBe(false);
    });

    it('should initialize errorMessage as empty', () => {
      expect(component.errorMessage()).toBe('');
    });

    it('should clear errorMessage when submitting starts', () => {
      // Arrange
      component.errorMessage.set('Some error');
      component.form.patchValue({ username: 'user', password: 'pass' });
      mockAuthService.login.mockReturnValue(of({ accessToken: 'token' }));

      // Act
      component.onSubmit();

      // Assert
      // errorMessage is cleared at the start of a valid submit
      expect(component.errorMessage()).toBe('');
    });
  });
});
