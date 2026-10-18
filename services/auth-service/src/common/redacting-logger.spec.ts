import { redact } from './redacting-logger.js';

describe('redact', () => {
  it('redacts a password field nested inside another object', () => {
    const input = {
      username: 'priya.menon',
      credentials: {
        password: 'correct horse battery staple',
      },
    };

    const output = redact(input) as any;

    expect(output.credentials.password).toBe('[REDACTED]');
    expect(output.username).toBe('priya.menon');
  });

  it('redacts a token nested several levels deep, inside an array', () => {
    const input = {
      request: {
        body: [{ session: { refreshToken: 'super-secret-refresh-token' } }],
      },
    };

    const output = redact(input) as any;

    expect(output.request.body[0].session.refreshToken).toBe('[REDACTED]');
  });

  it('redacts case-insensitively and across separator styles', () => {
    const input = {
      accessToken: 'a',
      access_token: 'b',
      Authorization: 'c',
      TOKEN: 'd',
    };

    const output = redact(input) as any;

    expect(output.accessToken).toBe('[REDACTED]');
    expect(output.access_token).toBe('[REDACTED]');
    expect(output.Authorization).toBe('[REDACTED]');
    expect(output.TOKEN).toBe('[REDACTED]');
  });

  it('leaves unrelated fields untouched, at every depth', () => {
    const input = { a: { b: { c: { username: 'priya.menon', count: 3 } } } };

    const output = redact(input) as any;

    expect(output.a.b.c.username).toBe('priya.menon');
    expect(output.a.b.c.count).toBe(3);
  });

  it('does not mutate the original object', () => {
    const input = { password: 'secret' };
    redact(input);
    expect(input.password).toBe('secret');
  });
});
