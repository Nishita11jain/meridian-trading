import { ExceptionFilter, Catch, ArgumentsHost, HttpException, HttpStatus } from '@nestjs/common';
import { ThrottlerException } from '@nestjs/throttler';
import { Request, Response } from 'express';
import { RedactingLogger } from './redacting-logger.js';

@Catch()
export class HttpExceptionFilter implements ExceptionFilter {
  private readonly logger = new RedactingLogger(HttpExceptionFilter.name);

  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp();
    const response = ctx.getResponse<Response>();
    const request = ctx.getRequest<Request>();

    // ThrottlerException is an HttpException (status 429) and would
    // otherwise fall into the generic branch below, which mislabels every
    // non-401/409/422 status as VAL-422 -- so a throttled request got a
    // 429 status with a body claiming "Invalid input", which is wrong on
    // its face and misleading to any client reading the body instead of
    // the status. RATE-429 is NOT one of the codes contracts/api-schemas/auth-api.yaml
    // defines (its ErrorResponse.errorCode enum only lists AUTH-401,
    // AUTH-409, VAL-422) -- that's a gap in the contract itself, not
    // something this filter can fix on its own; it needs a team decision
    // and a contract update, tracked separately.
    if (exception instanceof ThrottlerException) {
      this.logger.warn(`Rate limited: ${request.method} ${request.url} (IP: ${this.getClientIp(request)})`);
      return response.status(429).json({ errorCode: 'RATE-429', message: 'Too many attempts, try again later' });
    }

    if (exception instanceof HttpException) {
      const status = exception.getStatus();
      if (status === 422) {
        // Log validation errors: missing fields, type mismatches, constraint violations
        const errorResponse = exception.getResponse();
        const details = typeof errorResponse === 'object' && 'message' in errorResponse ? errorResponse.message : 'Unknown';
        this.logger.warn(
          `Validation failed on ${request.method} ${request.url}: ${JSON.stringify(details)} (IP: ${this.getClientIp(request)})`,
        );
        return response.status(422).json({ errorCode: 'VAL-422', message: 'Invalid input' });
      }
      if (status === 401) {
        this.logger.warn(`Unauthorized access attempt on ${request.method} ${request.url} (IP: ${this.getClientIp(request)})`);
        return response.status(401).json({ errorCode: 'AUTH-401', message: 'Unauthorised' });
      }
      if (status === 409) {
        this.logger.warn(`Conflict on ${request.method} ${request.url} (IP: ${this.getClientIp(request)})`);
        return response.status(409).json({ errorCode: 'AUTH-409', message: 'Registration failed' });
      }
      // Fallback for other HttpExceptions -- a malformed JSON body (400 from
      // Express's body-parser) and an unmapped route/method (404/405) both
      // land here. contracts/api-schemas/auth-api.yaml has no code for either case, but
      // it does fix 422 as VAL-422's status wherever VAL-422 appears, so the
      // response has to say 422 rather than pass through whatever status
      // this exception happened to carry -- a body claiming VAL-422 next to
      // a 400 or 404 status is a contract violation on its own, independent
      // of which code is "right" for a route the contract doesn't define.
      this.logger.warn(
        `Unmapped status ${status} on ${request.method} ${request.url}, normalised to 422 (IP: ${this.getClientIp(request)})`,
      );
      return response.status(422).json({ errorCode: 'VAL-422', message: 'Invalid input' });
    }

    // Genuinely unexpected (non-HTTP) exception: log the exception itself --
    // message and stack only, never the request body or any credential field
    // -- so a 500 is diagnosable. Reported as a 500, not the 422 the branches
    // above use for bad input. INT-500 is, like RATE-429, not in
    // contracts/api-schemas/auth-api.yaml's errorCode enum; that needs a contract update.
    if (exception instanceof Error) {
      this.logger.error(
        `Unhandled exception on ${request.method} ${request.url} (IP: ${this.getClientIp(request)}): ${exception.message}`,
        exception.stack,
      );
    } else {
      this.logger.error(`Unhandled non-Error exception on ${request.method} ${request.url} (IP: ${this.getClientIp(request)}): ${String(exception)}`);
    }
    response.status(HttpStatus.INTERNAL_SERVER_ERROR).json({ errorCode: 'INT-500', message: 'Internal server error' });
  }

  /**
   * Extract client IP address from request headers.
   * Handles X-Forwarded-For (proxy), X-Real-IP, and direct connection.
   */
  private getClientIp(request: Request): string {
    const forwarded = request.get('x-forwarded-for');
    if (forwarded) return forwarded.split(',')[0].trim();
    const realIp = request.get('x-real-ip');
    if (realIp) return realIp;
    return request.ip ?? 'unknown';
  }
}
