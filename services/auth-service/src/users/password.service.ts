import { Injectable } from '@nestjs/common';
import * as argon2 from 'argon2';

// Cost parameters. Benchmarked directly on the dev machine building this
// service (5 verify() calls, averaged) before picking a value, rather than
// assuming a number:
//
//   timeCost=2, memoryCost=19456 KiB (19 MiB) -> 34.1ms
//   timeCost=3, memoryCost=19456 KiB (19 MiB) -> 43.3ms
//   timeCost=2, memoryCost=65536 KiB (64 MiB) -> 135.9ms  <- chosen
//   timeCost=3, memoryCost=65536 KiB (64 MiB) -> 177.2ms
//
// memoryCost is the lever that matters against an offline attacker --
// argon2id's advantage over bcrypt is that its memory requirement doesn't
// parallelise cheaply on a GPU/ASIC, so 64 MiB (OWASP's higher-security
// baseline, double their 19 MiB minimum) was chosen over raising timeCost
// further. 136ms lands just past the "order of a tenth of a second" task.md
// asks for; timeCost was kept at 2 rather than 3 (which would push it to
// ~177ms) so the login route doesn't become the DoS-cheapest endpoint in
// the platform on slower hardware than this dev machine.
export const ARGON2_OPTIONS: argon2.HashOptions = {
  type: argon2.argon2id,
  timeCost: 2,
  memoryCost: 65536,
  parallelism: 1,
};

@Injectable()
export class PasswordService {
  hash(plain: string): Promise<string> {
    return argon2.hash(plain, ARGON2_OPTIONS);
  }

  verify(hash: string, plain: string): Promise<boolean> {
    return argon2.verify(hash, plain);
  }
}
