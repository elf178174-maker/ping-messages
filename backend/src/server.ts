import { config } from './config.js';
import { buildApp } from './app.js';
import { closePool, query } from './db/pool.js';
import { migrate } from './db/migrate.js';

/**
 * Process entry point.
 *
 * Migrations run before the server accepts traffic, so a deployment can never
 * serve requests against a schema it does not match.
 */
async function main(): Promise<void> {
  await migrate();

  const app = await buildApp();

  // Housekeeping: expired disappearing messages and status posts are deleted
  // on a timer rather than lazily, so nothing lingers on disk past its
  // lifetime even if no client asks for it.
  const cleanup = setInterval(() => void runCleanup(), 5 * 60 * 1000);
  cleanup.unref();

  const shutdown = async (signal: string) => {
    app.log.info({ signal }, 'shutting down');
    clearInterval(cleanup);
    await app.close();
    await closePool();
    process.exit(0);
  };

  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGINT', () => void shutdown('SIGINT'));

  await app.listen({ port: config.PORT, host: config.HOST });
  app.log.info(
    { url: config.PUBLIC_BASE_URL, media: config.MEDIA_DRIVER, email: config.EMAIL_DRIVER },
    'ping-backend listening',
  );
}

async function runCleanup(): Promise<void> {
  const now = Date.now();
  try {
    await query("UPDATE messages SET deleted_at = $1, body = '' WHERE expires_at IS NOT NULL AND expires_at <= $1 AND deleted_at IS NULL", [now]);
    await query('DELETE FROM status_posts WHERE expires_at <= $1', [now]);
    await query('DELETE FROM verification_codes WHERE expires_at <= $1', [now]);
    await query('DELETE FROM devices WHERE revoked_at IS NOT NULL AND revoked_at < $1', [
      now - 30 * 24 * 60 * 60 * 1000,
    ]);
  } catch (error) {
    console.error('[cleanup] failed', error);
  }
}

main().catch((error) => {
  console.error('[server] failed to start');
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
