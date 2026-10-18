import { Injectable } from '@nestjs/common';
import jwt from 'jsonwebtoken';

// Read lazily, at the point of use, not as a top-level module constant.
// ES module imports are fully evaluated before the importing file's own
// body runs, so app.module.ts's static `import { TokenService } from
// './tokens/token.service.js'` -- and everything that file pulls in --
// resolves before ConfigModule.forRoot() (itself just an argument
// evaluated later, inside app.module.ts's own @Module decorator) has any
// chance to load .env into process.env. A top-level read/validate here
// always lost that race and crashed the app on every boot, regardless of
// what the secret's actual value was.
export function getJwtSecret(): string {
  const secret = process.env.JWT_SECRET;
  if (!secret || secret.length < 32) {
    throw new Error('JWT_SECRET must be set and at least 32 characters long');
  }
  return secret;
}

// Ours to choose (task.md: "set your own issuer value ... don't require a
// particular issuer value in any consumer"). Read from the environment so
// it's one configured value rather than a literal repeated at every call
// site -- SEC2-679's guard checks a token's iss against this same constant.
export const JWT_ISSUER = process.env.JWT_ISSUER ?? 'auth-service';

const ACCESS_TOKEN_EXPIRES_IN = '15m';

export type TokenSubject = {
  id: string;
  accountId: number;
  roles: string[];
};

export type TokenPayload = TokenSubject & {
  sub: string;
  iat: number;
  exp: number;
  iss: string;
};

@Injectable()
export class TokenService {
  /**
   * Signs an access token carrying exactly the contract's claim set:
   * sub, accountId, roles, iat, exp (iat/exp set by the `expiresIn` option
   * below, not by hand -- jsonwebtoken derives both from the same clock, so
   * they can't drift relative to each other), plus the iss this service
   * defines. Nothing else rides along: no email, no username, no internal
   * field beyond what the contract names.
   */
  signAccessToken(user: TokenSubject): string {
    return jwt.sign(
      {
        sub: user.id,
        accountId: user.accountId,
        roles: user.roles,
        iss: JWT_ISSUER,
      },
      getJwtSecret(),
      { algorithm: 'HS256', expiresIn: ACCESS_TOKEN_EXPIRES_IN },
    );
  }

  /**
   * Verifies the signature, the expiry and the issuer -- in that order,
   * inside jsonwebtoken's own verify(), never decode() -- and returns the
   * decoded claims only if all three hold. Throws otherwise; callers (the
   * guard) are expected to turn that into a uniform AUTH-401, never a more
   * specific message. A guard that checks the signature and forgets the
   * clock accepts every token it ever issued, forever -- pinning
   * `algorithms: ['HS256']` closes the matching hole where a verifier
   * that trusts the token's own header could be tricked into checking an
   * attacker-chosen algorithm instead.
   */
  verify(token: string): TokenPayload {
    return jwt.verify(token, getJwtSecret(), {
      algorithms: ['HS256'],
      issuer: JWT_ISSUER,
    }) as TokenPayload;
  }
}
