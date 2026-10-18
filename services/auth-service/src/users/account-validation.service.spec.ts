import { jest } from '@jest/globals';
import jwt from 'jsonwebtoken';
import { AccountValidationService } from './account-validation.service.js';

describe('AccountValidationService', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
    jest.restoreAllMocks();
  });

  it('returns true when the account check responds ok', async () => {
    global.fetch = jest.fn().mockResolvedValue({ ok: true, status: 200 } as Response);
    const service = new AccountValidationService();

    await expect(service.isUsable(1)).resolves.toBe(true);
  });

  it('returns false when the account does not exist (404)', async () => {
    global.fetch = jest.fn().mockResolvedValue({ ok: false, status: 404 } as Response);
    const service = new AccountValidationService();

    await expect(service.isUsable(999)).resolves.toBe(false);
  });

  it('returns false when the account is not active (403)', async () => {
    global.fetch = jest.fn().mockResolvedValue({ ok: false, status: 403 } as Response);
    const service = new AccountValidationService();

    await expect(service.isUsable(3)).resolves.toBe(false);
  });

  it('returns false, not throwing, when trade-api-service is unreachable', async () => {
    global.fetch = jest.fn().mockRejectedValue(new Error('connect ECONNREFUSED'));
    const service = new AccountValidationService();

    await expect(service.isUsable(1)).resolves.toBe(false);
  });

  it('calls the accounts endpoint for the exact accountId being checked', async () => {
    const fetchMock = jest.fn().mockResolvedValue({ ok: true, status: 200 } as Response);
    global.fetch = fetchMock;
    const service = new AccountValidationService();

    await service.isUsable(42);

    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe('http://localhost:8081/api/v1/accounts/42');
  });

  it('signs the internal check token with the shared secret, HS256, and a short expiry', async () => {
    const fetchMock = jest.fn().mockResolvedValue({ ok: true, status: 200 } as Response);
    global.fetch = fetchMock;
    const service = new AccountValidationService();

    await service.isUsable(7);

    const [, options] = fetchMock.mock.calls[0] as [string, { headers: Record<string, string> }];
    const token = options.headers.Authorization.replace('Bearer ', '');
    const decoded = jwt.verify(token, process.env.JWT_SECRET as string) as jwt.JwtPayload;

    expect(decoded.accountId).toBe(7);
    expect(decoded.iss).toBe('auth-service');
    expect(decoded.roles).toEqual(['CUSTOMER']);
    expect(decoded.exp! - decoded.iat!).toBe(30);
  });

  it('never returns the internal check token to the caller', async () => {
    const fetchMock = jest.fn().mockResolvedValue({ ok: true, status: 200 } as Response);
    global.fetch = fetchMock;
    const service = new AccountValidationService();

    const result = await service.isUsable(1);

    expect(result).toBe(true);
    expect(typeof result).toBe('boolean');
  });
});
