import { Body, Controller, Get, Post, UseGuards, HttpCode, HttpException } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { AuthService } from './auth.service.js';
import { RegisterRequest } from './dto/register.dto.js';
import { LoginRequest } from './dto/login.dto.js';
import { RefreshRequest } from './dto/refresh.dto.js';
import { JwtAuthGuard } from '../tokens/jwt-auth.guard.js';
import { CurrentUser } from '../tokens/current-user.decorator.js';
import type { TokenPayload } from '../tokens/token.service.js';

@ApiTags('Auth')
@Controller('auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @ApiOperation({ summary: 'Register a new CUSTOMER user linked to a usable account; no tokens are issued' })
  @ApiResponse({ status: 201, description: 'User created (id, username, accountId, roles, createdOn)' })
  @ApiResponse({ status: 409, description: 'AUTH-409: username taken or account already linked' })
  @ApiResponse({ status: 422, description: 'VAL-422: invalid input or accountId not usable' })
  @Post('register')
  async register(@Body() body: RegisterRequest) {
    try {
      const user = await this.authService.register(body);
      return user;
    } catch (err: any) {
      if (err.code === 'AUTH-409') {
        throw new HttpException({ errorCode: 'AUTH-409', message: 'Registration failed' }, 409);
      }
      throw new HttpException({ errorCode: 'VAL-422', message: 'Invalid input' }, 422);
    }
  }

  @ApiOperation({ summary: 'Verify username and password and issue an access token and refresh token' })
  @ApiResponse({ status: 200, description: 'Tokens issued (accessToken, refreshToken, tokenType, expiresIn)' })
  @ApiResponse({ status: 401, description: 'AUTH-401: unknown user or wrong password' })
  @ApiResponse({ status: 422, description: 'VAL-422: request body failed validation' })
  @ApiResponse({ status: 429, description: 'Too many requests (throttled)' })
  @Post('login')
  @HttpCode(200)
  @UseGuards(ThrottlerGuard)
  async login(@Body() body: LoginRequest) {
    const identifier = body.username;
    const tokens = await this.authService.login(identifier, body.password);
    if (!tokens) {
      throw new HttpException({ errorCode: 'AUTH-401', message: 'Unauthorised' }, 401);
    }
    return tokens;
  }

  @ApiOperation({ summary: 'Rotate a refresh token and issue a new access token and refresh token' })
  @ApiResponse({ status: 200, description: 'New tokens issued (accessToken, refreshToken, tokenType, expiresIn)' })
  @ApiResponse({ status: 401, description: 'AUTH-401: refresh token invalid, expired or revoked, or user no longer exists' })
  @ApiResponse({ status: 422, description: 'VAL-422: request body failed validation' })
  @Post('refresh')
  @HttpCode(200)
  async refresh(@Body() body: RefreshRequest) {
    const tokens = await this.authService.refresh(body.refreshToken);
    if (!tokens) {
      throw new HttpException({ errorCode: 'AUTH-401', message: 'Unauthorised' }, 401);
    }
    return tokens;
  }

  @ApiBearerAuth()
  @ApiOperation({ summary: 'Return the profile of the user identified by the bearer access token' })
  @ApiResponse({ status: 200, description: 'User profile (id, username, accountId, roles, createdOn)' })
  @ApiResponse({ status: 401, description: 'AUTH-401: missing/invalid token, or user no longer exists' })
  @Get('me')
  @UseGuards(JwtAuthGuard)
  async me(@CurrentUser() claims: TokenPayload) {
    const user = await this.authService.getUserById(claims.sub);
    if (!user) throw new HttpException({ errorCode: 'AUTH-401', message: 'Unauthorised' }, 401);
    return user;
  }
}
