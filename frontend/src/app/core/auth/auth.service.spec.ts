import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
import { SessionStore } from './session.store';
import { AuthService as GeneratedAuthService } from '../../clients/auth-client/api/auth.service';
import { ProfileService as GeneratedProfileService } from '../../clients/auth-client/api/profile.service';
import { of, throwError } from 'rxjs';

describe('AuthService', () => {
  let service: AuthService;
  let sessionStore: SessionStore;
  let mockGeneratedAuthClient: any;
  let mockGeneratedProfileClient: any;

  beforeEach(() => {
    // Clear sessionStorage before each test
    sessionStorage.clear();
    
    // Mock the generated clients
    mockGeneratedAuthClient = {
      login: vi.fn(),
      register: vi.fn(),
      refresh: vi.fn()
    };

    mockGeneratedProfileClient = {
      getCurrentUser: vi.fn()
    };

    // Set up TestBed with mocks
    TestBed.configureTestingModule({
      providers: [
        AuthService,
        SessionStore,
        { provide: GeneratedAuthService, useValue: mockGeneratedAuthClient },
        { provide: GeneratedProfileService, useValue: mockGeneratedProfileClient }
      ]
    });

    service = TestBed.inject(AuthService);
    sessionStore = TestBed.inject(SessionStore);
  });

  afterEach(() => {
    sessionStorage.clear();
  });

  describe('login()', () => {
    it('should call generated auth client with credentials', async () => {
      // Arrange
      const response = { accessToken: 'token123', refreshToken: 'refresh123' };
      mockGeneratedAuthClient.login.mockReturnValue(of(response));

      // Act
      await new Promise<void>((resolve) => {
        service.login('user', 'pass').subscribe(() => {
          // Assert
          expect(mockGeneratedAuthClient.login).toHaveBeenCalledWith({
            username: 'user',
            password: 'pass'
          });
          resolve();
        });
      });
    });

    it('should store tokens in SessionStore on success', async () => {
      // Arrange
      const response = { accessToken: 'token123', refreshToken: 'refresh123' };
      mockGeneratedAuthClient.login.mockReturnValue(of(response));

      // Act
      await new Promise<void>((resolve) => {
        service.login('user', 'pass').subscribe(() => {
          // Assert
          expect(sessionStore.accessToken()).toBe('token123');
          expect(sessionStore.refreshToken()).toBe('refresh123');
          resolve();
        });
      });
    });

    it('should update isAuthenticated signal on success', async () => {
      // Arrange
      const response = { accessToken: 'token123', refreshToken: 'refresh123' };
      mockGeneratedAuthClient.login.mockReturnValue(of(response));
      expect(service.isAuthenticated()).toBe(false);

      // Act
      await new Promise<void>((resolve) => {
        service.login('user', 'pass').subscribe(() => {
          // Assert
          expect(service.isAuthenticated()).toBe(true);
          resolve();
        });
      });
    });

    it('should propagate errors from generated client', async () => {
      // Arrange
      const error = { errorCode: 'AUTH-401', message: 'Invalid credentials' };
      mockGeneratedAuthClient.login.mockReturnValue(throwError(() => error));

      // Act
      await new Promise<void>((resolve, reject) => {
        service.login('user', 'wrong').subscribe({
          next: () => {
            reject(new Error('Should have failed'));
          },
          error: (err) => {
            try {
              // Assert
              expect(err).toEqual(error);
              expect(service.isAuthenticated()).toBe(false);
              resolve();
            } catch (e) {
              reject(e);
            }
          }
        });
      });
    });
  });

  describe('logout()', () => {
    it('should clear tokens from SessionStore', () => {
      // Arrange
      sessionStore.set({
        accessToken: 'token123',
        refreshToken: 'refresh123'
      });
      expect(service.isAuthenticated()).toBe(true);

      // Act
      service.logout();

      // Assert
      expect(sessionStore.accessToken()).toBe('');
      expect(sessionStore.refreshToken()).toBe('');
      expect(service.isAuthenticated()).toBe(false);
    });
  });

  describe('isAuthenticated signal', () => {
    it('should be false initially', () => {
      expect(service.isAuthenticated()).toBe(false);
    });

    it('should be true after successful login', async () => {
      // Arrange
      const response = { accessToken: 'token123', refreshToken: 'refresh123' };
      mockGeneratedAuthClient.login.mockReturnValue(of(response));

      // Act
      await new Promise<void>((resolve) => {
        service.login('user', 'pass').subscribe(() => {
          // Assert
          expect(service.isAuthenticated()).toBe(true);
          resolve();
        });
      });
    });

    it('should be false after logout', async () => {
      // Arrange
      const response = { accessToken: 'token123', refreshToken: 'refresh123' };
      mockGeneratedAuthClient.login.mockReturnValue(of(response));

      // Act & Assert
      await new Promise<void>((resolve) => {
        service.login('user', 'pass').subscribe(() => {
          expect(service.isAuthenticated()).toBe(true);
          service.logout();
          expect(service.isAuthenticated()).toBe(false);
          resolve();
        });
      });
    });
  });

  describe('accessToken signal', () => {
    it('should reflect the current access token', async () => {
      // Arrange
      const response = { accessToken: 'token-abc-123', refreshToken: 'refresh' };
      mockGeneratedAuthClient.login.mockReturnValue(of(response));

      // Act
      await new Promise<void>((resolve) => {
        service.login('user', 'pass').subscribe(() => {
          // Assert
          expect(service.accessToken()).toBe('token-abc-123');
          resolve();
        });
      });
    });
  });

  describe('register()', () => {
    it('should call generated auth client with account info', async () => {
      // Arrange
      const response = { sub: 'user-id', accountId: 42, roles: ['CUSTOMER'] };
      mockGeneratedAuthClient.register.mockReturnValue(of(response));

      // Act
      await new Promise<void>((resolve) => {
        service.register('newuser', 'pass', 42).subscribe(() => {
          // Assert
          expect(mockGeneratedAuthClient.register).toHaveBeenCalledWith({
            username: 'newuser',
            password: 'pass',
            accountId: 42
          });
          resolve();
        });
      });
    });
  });

  describe('refresh()', () => {
    it('should call generated auth client with stored refresh token', async () => {
      // Arrange
      sessionStore.set({ accessToken: 'old', refreshToken: 'refresh-token' });
      const response = { accessToken: 'new-token', refreshToken: 'new-refresh' };
      mockGeneratedAuthClient.refresh.mockReturnValue(of(response));

      // Act
      await new Promise<void>((resolve) => {
        service.refresh().subscribe(() => {
          // Assert
          expect(mockGeneratedAuthClient.refresh).toHaveBeenCalledWith({
            refreshToken: 'refresh-token'
          });
          resolve();
        });
      });
    });

    it('should update tokens on success', async () => {
      // Arrange
      sessionStore.set({ accessToken: 'old', refreshToken: 'refresh-token' });
      const response = { accessToken: 'new-token', refreshToken: 'new-refresh' };
      mockGeneratedAuthClient.refresh.mockReturnValue(of(response));

      // Act
      await new Promise<void>((resolve) => {
        service.refresh().subscribe(() => {
          // Assert
          expect(service.accessToken()).toBe('new-token');
          expect(sessionStore.refreshToken()).toBe('new-refresh');
          resolve();
        });
      });
    });

    it('should return observable error if no refresh token available', () => {
      // Arrange: no refresh token set
      expect(sessionStore.refreshToken()).toBe('');

      // Act & Assert
      service.refresh().subscribe({
        next: () => {
          throw new Error('Should have errored');
        },
        error: (err: any) => {
          expect(err.message).toContain('No refresh token available');
        }
      });
    });
  });

  describe('getProfile()', () => {
    it('should call generated profile client', async () => {
      // Arrange
      const response = { sub: 'user-id', accountId: 1, roles: ['CUSTOMER'] };
      mockGeneratedProfileClient.getCurrentUser.mockReturnValue(of(response));

      // Act
      await new Promise<void>((resolve) => {
        service.getProfile().subscribe(() => {
          // Assert
          expect(mockGeneratedProfileClient.getCurrentUser).toHaveBeenCalled();
          resolve();
        });
      });
    });
  });
});
