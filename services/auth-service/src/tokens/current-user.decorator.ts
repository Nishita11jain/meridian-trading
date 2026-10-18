import { createParamDecorator, type ExecutionContext } from '@nestjs/common';
import type { AuthenticatedRequest } from './jwt-auth.guard.js';

/**
 * The verified token's claims, set by JwtAuthGuard. Only ever present on a
 * route the guard has already run on -- reading this without the guard in
 * front of it is a routing mistake, not a runtime check this decorator
 * makes.
 */
export const CurrentUser = createParamDecorator((_data: unknown, context: ExecutionContext) => {
  const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
  return request.user;
});
