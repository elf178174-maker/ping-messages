import { readdir, readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pool, query, transaction } from './pool.js';

const here = dirname(fileURLToPath(import.meta.url));

/**
 * Applies every migration that has not run yet, in filename order.
 *
 * Each migration runs inside its own transaction and records itself in
 * `schema_migrations` in that same transaction, so a failure part-way leaves
 * neither a half-applied schema nor a false record of success.
 */
export async function migrate(): Promise<string[]> {
  await query(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version    TEXT PRIMARY KEY,
      applied_at BIGINT NOT NULL
    )
  `);

  const applied = new Set(
    (await query<{ version: string }>('SELECT version FROM schema_migrations')).map(
      (row) => row.version,
    ),
  );

  const dir = join(here, 'migrations');
  const files = (await readdir(dir)).filter((name) => name.endsWith('.sql')).sort();

  const ran: string[] = [];
  for (const file of files) {
    if (applied.has(file)) continue;
    const sql = await readFile(join(dir, file), 'utf8');

    await transaction(async (client) => {
      await client.query(sql);
      await client.query('INSERT INTO schema_migrations (version, applied_at) VALUES ($1, $2)', [
        file,
        Date.now(),
      ]);
    });

    ran.push(file);
    console.log(`[migrate] applied ${file}`);
  }

  if (ran.length === 0) console.log('[migrate] already up to date');
  return ran;
}

// Running this file directly is the `npm run migrate` entry point.
if (import.meta.url === `file://${process.argv[1]}`) {
  migrate()
    .then(() => pool.end())
    .then(() => process.exit(0))
    .catch((error) => {
      console.error('[migrate] failed', error);
      process.exit(1);
    });
}
