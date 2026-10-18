import { Inject, Injectable } from '@nestjs/common';
import type { Pool } from 'pg';
import { PG_POOL, queryPool } from '../database/pg-pool.provider.js';

export type UserRecord = {
  id: string;
  username: string;
  passwordHash: string;
  accountId: number;
  roles: string[];
  createdOn: string;
};

export type NewUser = {
  id: string;
  username: string;
  passwordHash: string;
  accountId: number;
  roles: string[];
};

type UserRow = {
  id: string;
  username: string;
  password_hash: string;
  account_id: string | number;
  roles: string[];
  created_at: Date;
};

// Postgres error code for a UNIQUE constraint violation. Used to translate
// a duplicate username or an already-claimed accountId into a proper
// AUTH-409, rather than letting either surface as an unhandled 500.
const UNIQUE_VIOLATION = '23505';

export class UsernameTakenError extends Error {}
export class AccountAlreadyLinkedError extends Error {}

@Injectable()
export class UsersRepository {
  constructor(@Inject(PG_POOL) private readonly pool: Pool) {}

  async create(user: NewUser): Promise<UserRecord> {
    try {
      const result = await queryPool<UserRow>(
        this.pool,
        `INSERT INTO auth.users (id, username, password_hash, account_id, roles)
         VALUES ($1, $2, $3, $4, $5)
         RETURNING id, username, password_hash, account_id, roles, created_at`,
        [user.id, user.username, user.passwordHash, user.accountId, user.roles],
      );
      return this.toRecord(result.rows[0]);
    } catch (err: unknown) {
      const constraint = this.uniqueViolationConstraint(err);
      if (constraint === 'uq_auth_users_username') {
        throw new UsernameTakenError(user.username);
      }
      if (constraint === 'uq_auth_users_account_id') {
        throw new AccountAlreadyLinkedError(String(user.accountId));
      }
      throw err;
    }
  }

  async findByUsername(username: string): Promise<UserRecord | null> {
    const result = await queryPool<UserRow>(
      this.pool,
      `SELECT id, username, password_hash, account_id, roles, created_at
       FROM auth.users WHERE username = $1`,
      [username],
    );
    return result.rows[0] ? this.toRecord(result.rows[0]) : null;
  }

  async findById(id: string): Promise<UserRecord | null> {
    const result = await queryPool<UserRow>(
      this.pool,
      `SELECT id, username, password_hash, account_id, roles, created_at
       FROM auth.users WHERE id = $1`,
      [id],
    );
    return result.rows[0] ? this.toRecord(result.rows[0]) : null;
  }

  private uniqueViolationConstraint(err: unknown): string | undefined {
    if (typeof err !== 'object' || err === null) return undefined;
    const pgErr = err as { code?: string; constraint?: string };
    return pgErr.code === UNIQUE_VIOLATION ? pgErr.constraint : undefined;
  }

  private toRecord(row: UserRow): UserRecord {
    return {
      id: row.id,
      username: row.username,
      passwordHash: row.password_hash,
      // pg returns BIGINT as a string by default to avoid silent precision
      // loss; account_id fits safely in a JS number (Postgres BIGINT max is
      // far beyond any realistic account count here), and the JWT claim
      // this feeds into (SEC2-678) must be a JSON number, not a string.
      accountId: Number(row.account_id),
      roles: row.roles,
      createdOn: row.created_at.toISOString(),
    };
  }
}
