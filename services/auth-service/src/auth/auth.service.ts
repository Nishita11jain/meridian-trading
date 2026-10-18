import { Injectable } from '@nestjs/common';
import { v4 as uuidv4 } from 'uuid';
import { UsersRepository, UsernameTakenError, AccountAlreadyLinkedError } from '../users/users.repository.js';
import { PasswordService } from '../users/password.service.js';
import { AccountValidationService } from '../users/account-validation.service.js';
import { TokenService } from '../tokens/token.service.js';
import { RefreshTokenService } from '../tokens/refresh-token.service.js';
import { DummyHashService } from './dummy-hash.js';

@Injectable()
export class AuthService {
  constructor(
    private readonly usersRepository: UsersRepository,
    private readonly passwordService: PasswordService,
    private readonly accountValidation: AccountValidationService,
    private readonly tokenService: TokenService,
    private readonly refreshTokenService: RefreshTokenService,
    private readonly dummyHashService: DummyHashService,
  ) {}

  async register(data: any) {
    const usable = await this.accountValidation.isUsable(data.accountId);
    if (!usable) {
      const err: any = new Error('accountId does not refer to a usable account');
      err.code = 'VAL-422';
      throw err;
    }

    const id = uuidv4();
    const passwordHash = await this.passwordService.hash(data.password);
    try {
      const user = await this.usersRepository.create({
        id,
        username: data.username,
        passwordHash,
        accountId: data.accountId,
        // Never honour a client-supplied role on this public route --
        // contracts/api-schemas/auth-api.yaml is explicit that accepting one here is a
        // privilege-escalation bug. Every self-registration is CUSTOMER;
        // granting ADMIN is an operator action this route does not expose.
        roles: ['CUSTOMER'],
      });
      // Do not issue tokens on registration — return user response
      return {
        id: user.id,
        username: user.username,
        accountId: user.accountId,
        roles: user.roles,
        createdOn: user.createdOn,
      };
    } catch (err) {
      if (err instanceof UsernameTakenError || err instanceof AccountAlreadyLinkedError) {
        const conflict: any = new Error('Conflict');
        conflict.code = 'AUTH-409';
        throw conflict;
      }
      throw err;
    }
  }

  async login(identifier: string, password: string) {
    const user = await this.usersRepository.findByUsername(identifier);
    if (!user) {
      // SEC2-681: Constant-time failure response
      // Verify against dummy hash to burn the same ~100ms as real password verification
      // This prevents timing-based user enumeration attacks
      const dummyHash = this.dummyHashService.getDummyHash();
      await this.passwordService.verify(dummyHash, password);
      // Always return null for unknown user (regardless of whether dummy hash matched)
      return null;
    }
    const ok = await this.passwordService.verify(user.passwordHash, password);
    if (!ok) return null;

    const accessToken = this.tokenService.signAccessToken(user);
    const refreshToken = await this.refreshTokenService.issue(user.id);

    return {
      accessToken,
      refreshToken,
      tokenType: 'Bearer',
      expiresIn: 15 * 60,
    };
  }

  async refresh(refreshToken: string) {
    // Verify and rotate the refresh token (per SEC2-680)
    // This also looks up the userId from the stored token
    const result = await this.refreshTokenService.rotateWithUserLookup(refreshToken);
    if (!result) {
      return null; // Invalid, expired, or revoked token
    }

    const { userId, newRefreshToken } = result;
    const user = await this.usersRepository.findById(userId);
    if (!user) return null; // User deleted between token issuance and use

    const accessToken = this.tokenService.signAccessToken(user);

    return {
      accessToken,
      refreshToken: newRefreshToken,
      tokenType: 'Bearer',
      expiresIn: 15 * 60,
    };
  }

  // Token verification lives in TokenService/JwtAuthGuard now (SEC2-679) --
  // this is only the post-guard lookup, for the rare case a valid token
  // names a user who no longer exists (deleted between issuance and use).
  async getUserById(id: string) {
    const user = await this.usersRepository.findById(id);
    if (!user) return null;
    return {
      id: user.id,
      username: user.username,
      accountId: user.accountId,
      roles: user.roles,
      createdOn: user.createdOn,
    };
  }
}
