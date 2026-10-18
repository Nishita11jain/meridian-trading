import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { SessionStore } from './session.store';

describe('SessionStore', () => {
  let store: SessionStore;

  beforeEach(() => {
    // Clear sessionStorage before each test
    sessionStorage.clear();
    store = new SessionStore();
  });

  afterEach(() => {
    sessionStorage.clear();
  });

  describe('initialization', () => {
    it('should load tokens from sessionStorage on construction', () => {
      // Arrange: pre-populate sessionStorage
      const tokens = {
        accessToken: 'test-access-token',
        refreshToken: 'test-refresh-token'
      };
      sessionStorage.setItem('tokens', JSON.stringify(tokens));

      // Act: create new store instance
      const newStore = new SessionStore();

      // Assert: tokens are loaded into signals
      expect(newStore.accessToken()).toBe('test-access-token');
      expect(newStore.refreshToken()).toBe('test-refresh-token');
      expect(newStore.isAuthenticated()).toBe(true);
    });

    it('should start with empty tokens if sessionStorage is empty', () => {
      expect(store.accessToken()).toBe('');
      expect(store.refreshToken()).toBe('');
      expect(store.isAuthenticated()).toBe(false);
    });

    it('should handle corrupt sessionStorage gracefully', () => {
      // Arrange: sessionStorage has invalid JSON
      sessionStorage.setItem('tokens', '{invalid json}');

      // Act: create new store (should not throw)
      const newStore = new SessionStore();

      // Assert: tokens are empty, no error
      expect(newStore.accessToken()).toBe('');
      expect(newStore.isAuthenticated()).toBe(false);
    });
  });

  describe('set()', () => {
    it('should store accessToken and refreshToken in signals', () => {
      // Act
      store.set({
        accessToken: 'new-access',
        refreshToken: 'new-refresh'
      });

      // Assert
      expect(store.accessToken()).toBe('new-access');
      expect(store.refreshToken()).toBe('new-refresh');
    });

    it('should persist tokens to sessionStorage', () => {
      // Act
      store.set({
        accessToken: 'test-access',
        refreshToken: 'test-refresh'
      });

      // Assert
      const stored = JSON.parse(sessionStorage.getItem('tokens') || '{}');
      expect(stored.accessToken).toBe('test-access');
      expect(stored.refreshToken).toBe('test-refresh');
    });

    it('should update isAuthenticated signal when token is set', () => {
      // Arrange
      expect(store.isAuthenticated()).toBe(false);

      // Act
      store.set({ accessToken: 'token' });

      // Assert
      expect(store.isAuthenticated()).toBe(true);
    });

    it('should handle missing refreshToken', () => {
      // Act
      store.set({ accessToken: 'token-only' });

      // Assert
      expect(store.accessToken()).toBe('token-only');
      expect(store.refreshToken()).toBe('');
    });
  });

  describe('clear()', () => {
    it('should clear all tokens from signals', () => {
      // Arrange
      store.set({
        accessToken: 'access',
        refreshToken: 'refresh'
      });
      expect(store.accessToken()).not.toBe('');

      // Act
      store.clear();

      // Assert
      expect(store.accessToken()).toBe('');
      expect(store.refreshToken()).toBe('');
      expect(store.isAuthenticated()).toBe(false);
    });

    it('should remove tokens from sessionStorage', () => {
      // Arrange
      store.set({
        accessToken: 'access',
        refreshToken: 'refresh'
      });

      // Act
      store.clear();

      // Assert
      const stored = sessionStorage.getItem('tokens');
      expect(stored).toBe(null);
    });
  });

  describe('isAuthenticated computed signal', () => {
    it('should be true when accessToken is set', () => {
      // Act
      store.set({ accessToken: 'token' });

      // Assert
      expect(store.isAuthenticated()).toBe(true);
    });

    it('should be false when accessToken is empty', () => {
      // Assert
      expect(store.isAuthenticated()).toBe(false);
    });

    it('should update reactively when token changes', () => {
      // Arrange
      expect(store.isAuthenticated()).toBe(false);

      // Act & Assert 1
      store.set({ accessToken: 'token' });
      expect(store.isAuthenticated()).toBe(true);

      // Act & Assert 2
      store.clear();
      expect(store.isAuthenticated()).toBe(false);
    });
  });
});
