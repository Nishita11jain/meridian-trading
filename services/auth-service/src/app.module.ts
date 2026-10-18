import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { ThrottlerModule } from '@nestjs/throttler';
import { loginThrottleOptions } from './common/login-throttle.js';
import { createObserveModule } from '@nestjs/observe';
import { AuthController } from './auth/auth.controller.js';
import { AuthService } from './auth/auth.service.js';
import { DummyHashService } from './auth/dummy-hash.js';
import { pgPoolProvider } from './database/pg-pool.provider.js';
import { UsersRepository } from './users/users.repository.js';
import { PasswordService } from './users/password.service.js';
import { AccountValidationService } from './users/account-validation.service.js';
import { TokenService } from './tokens/token.service.js';
import { JwtAuthGuard } from './tokens/jwt-auth.guard.js';
import { RefreshTokenService } from './tokens/refresh-token.service.js';
import { RefreshTokenRepository } from './tokens/refresh-token.repository.js';

export const { ObserveModule, ObserveInstrument } = createObserveModule();

@Module({
  imports: [
    ConfigModule.forRoot({ envFilePath: '../../.env' }),
    ThrottlerModule.forRootAsync({
      imports: [ConfigModule],
      inject: [ConfigService],
      useFactory: (configService: ConfigService) => {
        const ttlSeconds = configService.get<number>('LOGIN_THROTTLE_TTL_SECONDS', 900);
        const limit = configService.get<number>('LOGIN_THROTTLE_LIMIT', 5);
        return loginThrottleOptions(ttlSeconds, limit);
      },
    }),
    ObserveModule.forRoot({
      appKey: 'YOUR_APP_KEY',
      appSecret: 'YOUR_APP_SECRET',
      serviceId: 'auth-service',
    }),
  ],
  controllers: [AuthController],
  providers: [
    AuthService,
    DummyHashService,
    pgPoolProvider,
    UsersRepository,
    PasswordService,
    AccountValidationService,
    TokenService,
    RefreshTokenService,
    RefreshTokenRepository,
    JwtAuthGuard,
  ],
})
export class AppModule {}
