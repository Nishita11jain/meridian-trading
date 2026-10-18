import { DummyHashService } from './dummy-hash.js';
import { PasswordService } from '../users/password.service.js';

/** The algorithm, version and cost parameters of an encoded hash, with the parameters in a fixed order. */
const costOf = (encoded: string) => {
  const [, algorithm, version, parameters] = encoded.split('$');
  return [algorithm, version, ...parameters.split(',').sort()].join('|');
};

describe('DummyHashService', () => {
  let dummy: DummyHashService;

  beforeAll(async () => {
    dummy = new DummyHashService();
    await dummy.onModuleInit();
  });

  it('is an argon2id hash with the same memory, time and parallelism as a real password hash', async () => {
    const real = await new PasswordService().hash('a-real-password-1234');

    expect(costOf(dummy.getDummyHash())).toBe(costOf(real));
  });

  it('records parallelism 1, the value a real hash uses, not the library default of 4', () => {
    const parameters = dummy.getDummyHash().split('$')[3].split(',');

    expect(parameters.sort()).toEqual(['m=65536', 'p=1', 't=2']);
  });

  it('refuses to be used before it has been generated', () => {
    expect(() => new DummyHashService().getDummyHash()).toThrow();
  });
});
