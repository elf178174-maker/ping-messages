import pg from 'pg';
import { config } from '../config.js';

const { Pool } = pg;

/**
 * The Postgres connection pool.
 *
 * BIGINT columns are parsed as JavaScript numbers rather than strings. Every
 * BIGINT in this schema is either an epoch-millisecond timestamp or a sequence
 * counter, both comfortably inside Number.MAX_SAFE_INTEGER (which epoch-ms
 * does not reach until the year 287396), so the precision loss that makes this
 * a bad idea in general does not apply here — and it keeps every timestamp a
 * number end to end instead of a string that has to be parsed at each use.
 */
pg.types.setTypeParser(pg.types.builtins.INT8, (value) => Number(value));

export const pool = new Pool({
  connectionString: config.DATABASE_URL,
  // 10 is comfortable for a single API instance; the realtime gateway holds no
  // long-lived connections of its own, so this is the whole budget.
  max: 10,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 10_000,
  ssl: config.DATABASE_URL.includes('sslmode=require') ? { rejectUnauthorized: false } : undefined,
});

pool.on('error', (error) => {
  // An idle client erroring must not take the process down; the pool replaces it.
  console.error('[db] idle client error', error);
});

export type Queryable = Pick<pg.PoolClient, 'query'>;

export async function query<T extends pg.QueryResultRow = pg.QueryResultRow>(
  text: string,
  params: unknown[] = [],
  client: Queryable = pool,
): Promise<T[]> {
  const result = await client.query<T>(text, params as never[]);
  return result.rows;
}

export async function queryOne<T extends pg.QueryResultRow = pg.QueryResultRow>(
  text: string,
  params: unknown[] = [],
  client: Queryable = pool,
): Promise<T | null> {
  const rows = await query<T>(text, params, client);
  return rows[0] ?? null;
}

/**
 * Runs [fn] inside a transaction, rolling back on any throw.
 *
 * Used for every multi-statement mutation — creating a group writes to four
 * tables, and a partial group is worse than none.
 */
export async function transaction<T>(fn: (client: pg.PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (error) {
    await client.query('ROLLBACK').catch(() => undefined);
    throw error;
  } finally {
    client.release();
  }
}

export async function closePool(): Promise<void> {
  await pool.end();
}
