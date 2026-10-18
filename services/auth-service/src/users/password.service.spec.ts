import { PasswordService } from './password.service.js';

describe('PasswordService', () => {
  const service = new PasswordService();

  it('verifies a correct password', async () => {
    const hash = await service.hash('correct horse battery staple');
    await expect(service.verify(hash, 'correct horse battery staple')).resolves.toBe(true);
  });

  it('fails verification for an incorrect password', async () => {
    const hash = await service.hash('correct horse battery staple');
    await expect(service.verify(hash, 'wrong password entirely')).resolves.toBe(false);
  });

  it('does not use a general-purpose digest such as MD5 or SHA', async () => {
    const hash = await service.hash('correct horse battery staple');
    // argon2id hashes are self-describing PHC strings, e.g.
    // "$argon2id$v=19$m=65536,t=2,p=1$...". A bare MD5 (32 hex chars) or
    // SHA-256 (64 hex chars) digest has no "$"-delimited prefix at all.
    expect(hash.startsWith('$argon2id$')).toBe(true);
    expect(hash).not.toMatch(/^[a-f0-9]{32}$/i);
    expect(hash).not.toMatch(/^[a-f0-9]{64}$/i);
  });
});
