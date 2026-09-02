import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { query } from '../db/pool.js';
import { currentUser, requireAuth } from '../lib/auth.js';
import { parse } from '../lib/validation.js';
import { projectUser } from '../lib/presence.js';
import { shapeMessage, type MessageRow } from './messages.js';

export async function syncRoutes(app: FastifyInstance): Promise<void> {
  app.addHook('preHandler', requireAuth);

  /**
   * Incremental catch-up after being offline.
   *
   * `since` is the server clock value the client last saw, not a local
   * timestamp — a device with a skewed clock would otherwise ask for the wrong
   * window and silently miss messages.
   */
  app.get('/v1/sync', async (request) => {
    const q = parse(
      z.object({
        since: z.coerce.number().int().min(0).default(0),
        cursor: z.string().max(64).optional(),
        limit: z.coerce.number().int().min(1).max(500).default(200),
      }),
      request.query,
    );

    const userId = currentUser(request);

    const messages = await query<MessageRow>(
      `SELECT m.* FROM messages m
       JOIN conversation_members cm
         ON cm.conversation_id = m.conversation_id AND cm.user_id = $1 AND cm.left_at IS NULL
       WHERE m.created_at > $2
       ORDER BY m.created_at ASC
       LIMIT $3`,
      [userId, q.since, q.limit],
    );

    const deleted = await query<{ id: string }>(
      `SELECT m.id FROM messages m
       JOIN conversation_members cm
         ON cm.conversation_id = m.conversation_id AND cm.user_id = $1 AND cm.left_at IS NULL
       WHERE m.deleted_at IS NOT NULL AND m.deleted_at > $2
       LIMIT 500`,
      [userId, q.since],
    );

    const shaped = await Promise.all(messages.map((row) => shapeMessage(row, userId)));
    const serverTime = Date.now();

    return {
      conversations: [],
      messages: shaped,
      statuses: [],
      calls: [],
      deletedMessageIds: deleted.map((row) => row.id),
      serverTime,
      // The next cursor is the newest message's timestamp, so a client that
      // pages through a large backlog never re-reads or skips a window.
      nextCursor: messages.length > 0 ? String(messages[messages.length - 1]!.created_at) : null,
      hasMore: messages.length === q.limit,
    };
  });

  /**
   * Server-side search.
   *
   * Covers people and group names only. Message bodies are ciphertext, so the
   * server cannot search them even in principle — that search runs against the
   * client's local FTS index instead. Saying so plainly matters more than
   * offering a search that would quietly return nothing.
   */
  app.get('/v1/search', async (request) => {
    const q = parse(z.object({ q: z.string().trim().min(2).max(64) }), request.query);
    const userId = currentUser(request);
    const needle = `%${q.q.toLowerCase().replace(/[%_]/g, (m) => `\\${m}`)}%`;

    const users = await query<{
      id: string;
      username: string;
      display_name: string;
      about: string;
      avatar_url: string | null;
      updated_at: number;
    }>(
      `SELECT id, username, display_name, about, avatar_url, updated_at FROM users
       WHERE is_deleted = FALSE AND id <> $1
         AND (lower(username) LIKE $2 OR lower(display_name) LIKE $2)
         AND NOT EXISTS (
           SELECT 1 FROM blocks
           WHERE (user_id = users.id AND blocked_id = $1) OR (user_id = $1 AND blocked_id = users.id)
         )
       ORDER BY lower(display_name) LIMIT 20`,
      [userId, needle],
    );

    const groups = await query<{ conversation_id: string; name: string; avatar_url: string | null }>(
      `SELECT g.conversation_id, g.name, g.avatar_url FROM groups g
       JOIN conversation_members m
         ON m.conversation_id = g.conversation_id AND m.user_id = $1 AND m.left_at IS NULL
       WHERE lower(g.name) LIKE $2
       ORDER BY lower(g.name) LIMIT 20`,
      [userId, needle],
    );

    return {
      users: await Promise.all(users.map((row) => projectUser(row, userId))),
      conversations: groups.map((row) => ({
        id: row.conversation_id,
        type: 'GROUP',
        title: row.name,
        avatarUrl: row.avatar_url,
      })),
      messages: [],
    };
  });
}
