import { jest } from '@jest/globals';
import type { Pool } from 'pg';
import { UsersRepository, UsernameTakenError, AccountAlreadyLinkedError } from './users.repository.js';

function fakePool(
  handler: (text: string, params: unknown[]) => { rows: unknown[] } | never,
): Pool {
  return {
    query: jest.fn((text: string, params: unknown[], cb: (err: unknown, result?: unknown) => void) => {
      try {
        cb(null, handler(text, params));
      } catch (err) {
        cb(err);
      }
    }),
  } as unknown as Pool;
}

const newUser = {
  id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
  username: 'neel.verma',
  passwordHash: '$argon2id$...',
  accountId: 1,
  roles: ['CUSTOMER'],
};

describe('UsersRepository', () => {
  describe('create', () => {
    it('inserts and returns the created record, casting account_id to a number', async () => {
      const pool = fakePool(() => ({
        rows: [
          {
            id: newUser.id,
            username: newUser.username,
            password_hash: newUser.passwordHash,
            account_id: '1', // pg returns BIGINT as a string
            roles: newUser.roles,
            created_at: new Date('2026-01-01T00:00:00Z'),
          },
        ],
      }));
      const repo = new UsersRepository(pool);

      const record = await repo.create(newUser);

      expect(record.id).toBe(newUser.id);
      expect(record.accountId).toBe(1);
      expect(typeof record.accountId).toBe('number');
      expect(record.createdOn).toBe('2026-01-01T00:00:00.000Z');
    });

    it('passes the insert parameters in the right order', async () => {
      const pool = fakePool(() => ({
        rows: [
          {
            id: newUser.id,
            username: newUser.username,
            password_hash: newUser.passwordHash,
            account_id: 1,
            roles: newUser.roles,
            created_at: new Date(),
          },
        ],
      }));
      const repo = new UsersRepository(pool);

      await repo.create(newUser);

      expect(pool.query).toHaveBeenCalledWith(
        expect.stringContaining('INSERT INTO auth.users'),
        [newUser.id, newUser.username, newUser.passwordHash, newUser.accountId, newUser.roles],
        expect.any(Function),
      );
    });

    it('translates a unique-username violation into UsernameTakenError', async () => {
      const pool = fakePool(() => {
        throw { code: '23505', constraint: 'uq_auth_users_username' };
      });
      const repo = new UsersRepository(pool);

      await expect(repo.create(newUser)).rejects.toBeInstanceOf(UsernameTakenError);
    });

    it('translates a unique-account_id violation into AccountAlreadyLinkedError', async () => {
      const pool = fakePool(() => {
        throw { code: '23505', constraint: 'uq_auth_users_account_id' };
      });
      const repo = new UsersRepository(pool);

      await expect(repo.create(newUser)).rejects.toBeInstanceOf(AccountAlreadyLinkedError);
    });

    it('rethrows an unrelated database error untranslated', async () => {
      const dbError = new Error('connection reset');
      const pool = fakePool(() => {
        throw dbError;
      });
      const repo = new UsersRepository(pool);

      await expect(repo.create(newUser)).rejects.toBe(dbError);
    });

    it('rethrows a unique violation on a constraint it does not recognise', async () => {
      const pool = fakePool(() => {
        throw { code: '23505', constraint: 'some_other_constraint' };
      });
      const repo = new UsersRepository(pool);

      await expect(repo.create(newUser)).rejects.toEqual({ code: '23505', constraint: 'some_other_constraint' });
    });
  });

  describe('findByUsername', () => {
    it('returns the mapped record when found', async () => {
      const pool = fakePool(() => ({
        rows: [
          {
            id: newUser.id,
            username: newUser.username,
            password_hash: newUser.passwordHash,
            account_id: 1,
            roles: newUser.roles,
            created_at: new Date('2026-01-01T00:00:00Z'),
          },
        ],
      }));
      const repo = new UsersRepository(pool);

      const record = await repo.findByUsername('neel.verma');

      expect(record?.username).toBe('neel.verma');
      expect(pool.query).toHaveBeenCalledWith(expect.stringContaining('WHERE username = $1'), ['neel.verma'], expect.any(Function));
    });

    it('returns null when no row matches', async () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new UsersRepository(pool);

      await expect(repo.findByUsername('nobody')).resolves.toBeNull();
    });
  });

  describe('findById', () => {
    it('returns the mapped record when found', async () => {
      const pool = fakePool(() => ({
        rows: [
          {
            id: newUser.id,
            username: newUser.username,
            password_hash: newUser.passwordHash,
            account_id: 1,
            roles: newUser.roles,
            created_at: new Date('2026-01-01T00:00:00Z'),
          },
        ],
      }));
      const repo = new UsersRepository(pool);

      const record = await repo.findById(newUser.id);

      expect(record?.id).toBe(newUser.id);
      expect(pool.query).toHaveBeenCalledWith(expect.stringContaining('WHERE id = $1'), [newUser.id], expect.any(Function));
    });

    it('returns null when no row matches', async () => {
      const pool = fakePool(() => ({ rows: [] }));
      const repo = new UsersRepository(pool);

      await expect(repo.findById('does-not-exist')).resolves.toBeNull();
    });
  });
});
