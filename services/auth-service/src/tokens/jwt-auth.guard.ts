import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common';
import type { Request } from 'express';
import { TokenService, type TokenPayload } from './token.service.js';

export type AuthenticatedRequest = Request & { user?: TokenPayload };

const BEARER_PREFIX = 'Bearer ';

@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(private readonly tokenService: TokenService) {}

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const header = request.headers?.authorization;

    if (!header || !header.startsWith(BEARER_PREFIX)) {
      // Same AUTH-401 as every other failure below -- a missing header and
      // an expired token are indistinguishable from the outside, on
      // purpose (task.md: "one answer for every failure" extends to this
      // route too, even though that story is Ananya's).
      throw new UnauthorizedException();
    }

    const token = header.slice(BEARER_PREFIX.length);
    try {
      // verify() checks the signature before anything else -- a wrong
      // signature is refused by jsonwebtoken's own verify() before it
      // returns a payload at all, so no claim is ever read from an
      // unverified token.
      request.user = this.tokenService.verify(token);
      return true;
    } catch {
      throw new UnauthorizedException();
    }
  }
}
