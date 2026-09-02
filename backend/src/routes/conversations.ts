import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { query, queryOne, transaction } from '../db/pool.js';
import { assertMember, currentUser, isBlockedEitherWay, requireAuth } from '../lib/auth.js';
import { forbidden, notFound } from '../lib/errors.js';
import { directPairKey, newId } from '../lib/ids.js';
import { projectUser } from '../lib/presence.js';
import { idSchema, parse } from '../lib/validation.js';
import { hub } from '../realtime/hub.js';

export async function conversationRoutes(app: FastifyInstance): Promise<void> {
  app.addHook('preHandler', requireAuth);

  /**
   * The chat list.
   *
   * One query with lateral joins rather than N+1: for a user in 200
   * conversations, the naive version issues 601 queries to render one screen.
   */
  app.get('/v1/conversations', async (request) => {
    const userId = currentUser(request);

    const rows = await query<ConversationRow>(
      `SELECT
         c.id, c.type, c.disappearing_after_ms, c.pinned_message_id,
         c.created_at, c.updated_at, c.last_seq,
         m.last_read_seq,
         g.name  AS group_name,
         g.avatar_url AS group_avatar,
         other.id AS other_id, other.username AS other_username,
         other.display_name AS other_display_name, other.about AS other_about,
         other.avatar_url AS other_avatar, other.updated_at AS other_updated_at,
         last.id AS last_id, last.sender_id AS last_sender, last.kind AS last_kind,
         last.body AS last_body, last.created_at AS last_created_at,
         last.is_encrypted AS last_encrypted, last.deleted_at AS last_deleted_at,
         last.seq AS last_seq_value
       FROM conversation_members m
       JOIN conversations c ON c.id = m.conversation_id
       LEFT JOIN groups g ON g.conversation_id = c.id
       LEFT JOIN LATERAL (
         SELECT u.id, u.username, u.display_name, u.about, u.avatar_url, u.updated_at
         FROM conversation_members om
         JOIN users u ON u.id = om.user_id
         WHERE om.conversation_id = c.id AND om.user_id <> $1
         LIMIT 1
       ) other ON c.type = 'DIRECT'
       LEFT JOIN LATERAL (
         SELECT id, sender_id, kind, body, created_at, is_encrypted, deleted_at, seq
         FROM messages
         WHERE conversation_id = c.id
         ORDER BY seq DESC
         LIMIT 1
       ) last ON TRUE
       WHERE m.user_id = $1 AND m.left_at IS NULL
       ORDER BY COALESCE(last.created_at, c.updated_at) DESC
       LIMIT 500`,
      [userId],
    );

    return Promise.all(rows.map((row) => shapeConversation(row, userId)));
  });

  app.get('/v1/conversations/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const userId = currentUser(request);
    await assertMember(params.id, userId);

    const row = await queryOne<ConversationRow>(
      `SELECT
         c.id, c.type, c.disappearing_after_ms, c.pinned_message_id,
         c.created_at, c.updated_at, c.last_seq,
         m.last_read_seq,
         g.name AS group_name, g.avatar_url AS group_avatar,
         other.id AS other_id, other.username AS other_username,
         other.display_name AS other_display_name, other.about AS other_about,
         other.avatar_url AS other_avatar, other.updated_at AS other_updated_at,
         NULL::text AS last_id, NULL::text AS last_sender, NULL::text AS last_kind,
         NULL::text AS last_body, NULL::bigint AS last_created_at,
         NULL::boolean AS last_encrypted, NULL::bigint AS last_deleted_at,
         NULL::bigint AS last_seq_value
       FROM conversation_members m
       JOIN conversations c ON c.id = m.conversation_id
       LEFT JOIN groups g ON g.conversation_id = c.id
       LEFT JOIN LATERAL (
         SELECT u.id, u.username, u.display_name, u.about, u.avatar_url, u.updated_at
         FROM conversation_members om JOIN users u ON u.id = om.user_id
         WHERE om.conversation_id = c.id AND om.user_id <> $2
         LIMIT 1
       ) other ON c.type = 'DIRECT'
       WHERE c.id = $1 AND m.user_id = $2`,
      [params.id, userId],
    );
    if (!row) throw notFound('Conversation');

    return shapeConversation(row, userId);
  });

  /**
   * Opens or creates a conversation.
   *
   * For a one-to-one chat this is idempotent: the sorted pair key has a unique
   * index, so two devices racing to open the same chat converge on one
   * conversation instead of creating two.
   */
  app.post('/v1/conversations', async (request) => {
    const body = parse(
      z.object({
        participantIds: z.array(idSchema).min(1).max(1024),
        type: z.enum(['DIRECT', 'GROUP']).default('DIRECT'),
      }),
      request.body,
    );

    const userId = currentUser(request);
    const now = Date.now();

    if (body.type === 'DIRECT') {
      const otherId = body.participantIds[0]!;
      if (otherId === userId) throw forbidden('You cannot start a chat with yourself');

      const other = await queryOne('SELECT 1 FROM users WHERE id = $1 AND is_deleted = FALSE', [otherId]);
      if (!other) throw notFound('User');

      if (await isBlockedEitherWay(userId, otherId)) {
        throw forbidden('You cannot message this person');
      }

      const pairKey = directPairKey(userId, otherId);
      const existing = await queryOne<{ conversation_id: string }>(
        'SELECT conversation_id FROM direct_conversation_keys WHERE pair_key = $1',
        [pairKey],
      );

      const conversationId = existing?.conversation_id ?? newId();

      if (!existing) {
        await transaction(async (client) => {
          await client.query(
            'INSERT INTO conversations (id, type, created_at, updated_at) VALUES ($1, $2, $3, $3)',
            [conversationId, 'DIRECT', now],
          );
          await client.query(
            'INSERT INTO direct_conversation_keys (conversation_id, pair_key) VALUES ($1, $2)',
            [conversationId, pairKey],
          );
          await client.query(
            `INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
             VALUES ($1, $2, 'MEMBER', $4), ($1, $3, 'MEMBER', $4)`,
            [conversationId, userId, otherId, now],
          );
        });
      } else {
        // Rejoin if this user had previously deleted the conversation.
        await query(
          `INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
           VALUES ($1, $2, 'MEMBER', $3)
           ON CONFLICT (conversation_id, user_id) DO UPDATE SET left_at = NULL`,
          [conversationId, userId, now],
        );
      }

      const row = await queryOne<ConversationRow>(
        `SELECT c.id, c.type, c.disappearing_after_ms, c.pinned_message_id, c.created_at,
                c.updated_at, c.last_seq, 0::bigint AS last_read_seq,
                NULL::text AS group_name, NULL::text AS group_avatar,
                u.id AS other_id, u.username AS other_username, u.display_name AS other_display_name,
                u.about AS other_about, u.avatar_url AS other_avatar, u.updated_at AS other_updated_at,
                NULL::text AS last_id, NULL::text AS last_sender, NULL::text AS last_kind,
                NULL::text AS last_body, NULL::bigint AS last_created_at,
                NULL::boolean AS last_encrypted, NULL::bigint AS last_deleted_at,
                NULL::bigint AS last_seq_value
         FROM conversations c, users u
         WHERE c.id = $1 AND u.id = $2`,
        [conversationId, otherId],
      );
      if (!row) throw notFound('Conversation');
      return shapeConversation(row, userId);
    }

    throw forbidden('Use POST /v1/groups to create a group');
  });

  app.delete('/v1/conversations/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const userId = currentUser(request);

    // Leaving rather than deleting: the other participant's copy of the
    // conversation is theirs, and one person deleting must not remove it.
    await query(
      'UPDATE conversation_members SET left_at = $1 WHERE conversation_id = $2 AND user_id = $3',
      [Date.now(), params.id, userId],
    );
    return { ok: true };
  });

  app.put('/v1/conversations/:id/disappearing', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const queryParams = parse(
      z.object({ durationMs: z.coerce.number().int().positive().nullish() }),
      request.query,
    );
    const userId = currentUser(request);
    await assertMember(params.id, userId);

    await query('UPDATE conversations SET disappearing_after_ms = $1, updated_at = $2 WHERE id = $3', [
      queryParams.durationMs ?? null,
      Date.now(),
      params.id,
    ]);

    const members = await memberIds(params.id);
    hub.toUsers(members, {
      t: 'conversation',
      conversation: { id: params.id, type: 'DIRECT', disappearingAfterMs: queryParams.durationMs ?? null },
    });

    return { ok: true };
  });

  app.put('/v1/conversations/:id/pinned-message', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const queryParams = parse(z.object({ messageId: idSchema.nullish() }), request.query);
    const userId = currentUser(request);
    await assertMember(params.id, userId);

    await query('UPDATE conversations SET pinned_message_id = $1, updated_at = $2 WHERE id = $3', [
      queryParams.messageId ?? null,
      Date.now(),
      params.id,
    ]);
    return { ok: true };
  });
}

interface ConversationRow {
  id: string;
  type: 'DIRECT' | 'GROUP';
  disappearing_after_ms: number | null;
  pinned_message_id: string | null;
  created_at: number;
  updated_at: number;
  last_seq: number;
  last_read_seq: number;
  group_name: string | null;
  group_avatar: string | null;
  other_id: string | null;
  other_username: string | null;
  other_display_name: string | null;
  other_about: string | null;
  other_avatar: string | null;
  other_updated_at: number | null;
  last_id: string | null;
  last_sender: string | null;
  last_kind: string | null;
  last_body: string | null;
  last_created_at: number | null;
  last_encrypted: boolean | null;
  last_deleted_at: number | null;
  last_seq_value: number | null;
}

async function shapeConversation(row: ConversationRow, viewerId: string) {
  const otherUser =
    row.other_id !== null
      ? await projectUser(
          {
            id: row.other_id,
            username: row.other_username ?? '',
            display_name: row.other_display_name ?? '',
            about: row.other_about ?? '',
            avatar_url: row.other_avatar,
            updated_at: row.other_updated_at ?? 0,
          },
          viewerId,
        )
      : null;

  return {
    id: row.id,
    type: row.type,
    title: row.group_name ?? (otherUser?.displayName as string | undefined) ?? '',
    avatarUrl: row.group_avatar ?? (otherUser?.avatarUrl as string | null | undefined) ?? null,
    otherUser,
    // Derived rather than stored, so it cannot drift out of step with what the
    // member has actually read.
    unreadCount: Math.max(0, row.last_seq - row.last_read_seq),
    mentionCount: 0,
    disappearingAfterMs: row.disappearing_after_ms,
    pinnedMessageId: row.pinned_message_id,
    lastMessage:
      row.last_id !== null
        ? {
            id: row.last_id,
            conversationId: row.id,
            senderId: row.last_sender ?? '',
            kind: row.last_kind ?? 'TEXT',
            body: row.last_deleted_at !== null ? '' : (row.last_body ?? ''),
            isEncrypted: row.last_encrypted ?? true,
            isDeleted: row.last_deleted_at !== null,
            createdAt: row.last_created_at ?? 0,
            serverSeq: row.last_seq_value ?? 0,
          }
        : null,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

export async function memberIds(conversationId: string): Promise<string[]> {
  const rows = await query<{ user_id: string }>(
    'SELECT user_id FROM conversation_members WHERE conversation_id = $1 AND left_at IS NULL',
    [conversationId],
  );
  return rows.map((row) => row.user_id);
}
