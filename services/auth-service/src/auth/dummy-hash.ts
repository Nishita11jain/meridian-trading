import { hash } from 'argon2';
import { ARGON2_OPTIONS } from '../users/password.service.js';
import { Injectable, OnModuleInit } from '@nestjs/common';
import { RedactingLogger } from '../common/redacting-logger.js';

/**
 * SEC2-681: Dummy Hash Singleton
 *
 * Purpose: Prevent timing-based user enumeration on login endpoint.
 *
 * Problem: When user not found, returning immediately (no password check) takes ~1ms.
 * When password is wrong, Argon2 verification takes ~100ms.
 * Attacker can measure this difference to enumerate valid usernames.
 *
 * Solution: When user not found, verify a dummy Argon2 hash instead of returning early.
 * This burns ~100ms on both paths (user not found vs. wrong password).
 *
 * Implementation: Generate one dummy hash at service startup and cache in memory.
 * Every failed login (unknown user) calls: passwordService.verify(DUMMY_HASH, password)
 * This ensures identical timing for both failure paths.
 *
 * Generated once at module load. If Argon2 is broken, service fails to start (fail fast).
 */
@Injectable()
export class DummyHashService implements OnModuleInit {
  private dummyHash: string | null = null;
  private readonly logger = new RedactingLogger(DummyHashService.name);

  async onModuleInit(): Promise<void> {
    // Generate dummy hash at startup and cache it
    // Use a predictable input so we could regenerate if needed, but prefer cached
    const dummyPassword = 'dummy-password-for-constant-time-verification';
    try {
      // The very options PasswordService hashes real passwords with (type, memory, time AND
      // parallelism), so verifying against this hash costs the same as verifying a real one and an
      // unknown username cannot be told apart by timing.
      this.dummyHash = await hash(dummyPassword, ARGON2_OPTIONS);
      this.logger.log('Dummy hash generated for constant-time login verification');
    } catch (error) {
      this.logger.error('Failed to generate dummy hash at startup', error);
      throw new Error('DummyHashService initialization failed: Argon2 unavailable');
    }
  }

  /**
   * Get the cached dummy hash for use in constant-time verification.
   * Safe to call after onModuleInit (service fully initialized).
   * If called before init or init failed, throws.
   */
  getDummyHash(): string {
    if (!this.dummyHash) {
      throw new Error('DummyHashService not initialized');
    }
    return this.dummyHash;
  }
}
