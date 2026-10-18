import 'reflect-metadata';
import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import { RegisterRequest } from './register.dto.js';

describe('RegisterRequest', () => {
  const base = {
    username: 'priya.menon',
    password: 'correct horse battery staple',
    accountId: 1,
  };

  it('rejects a username containing a space with a validation error', async () => {
    const dto = plainToInstance(RegisterRequest, { ...base, username: 'priya menon' });
    const errors = await validate(dto);

    expect(errors.length).toBeGreaterThan(0);
    expect(errors.some((e) => e.property === 'username' && e.constraints?.matches)).toBe(true);
  });

  it('accepts a username made only of letters, digits, dot, underscore and hyphen', async () => {
    const dto = plainToInstance(RegisterRequest, { ...base, username: 'priya.menon_1-2' });
    const errors = await validate(dto);

    expect(errors).toHaveLength(0);
  });
});
