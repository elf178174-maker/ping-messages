import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { query, queryOne, transaction } from '../db/pool.js';
import { assertMember, currentUser, isBlockedEitherWay, requireAuth } from '../lib/auth.js';
import { badRequest, forbidden, notFound } from '../lib/errors.js';
import { newId } from '../lib/ids.js';
import { privacyFor } from '../lib/presence.js';
import { idSchema, parse } from '../lib/validation.js';
import { hub } from '../realtime/hub.js';
import { memberIds } from './conversations.js';

const attachmentSchema = z.object({
  id: idSchema,
  kind: z.string().max(32),
  url: z.string().max(2048).nullish(),
  fileName: z.string().max(255).default(''),
  mimeType: z.string().max(255).default('application/octet-stream'),
  sizeBytes: z.number().int().min(0).default(0),
  width: z.number().int().min(0).default(0),
  height: z.number().int().min(0).default(0),
  durationMs: z.number().int().min(0).default(0),
  blurHash: z.string().max(128).nullish(),
  waveform: z.array(z.number()).max(512).default([]),
  mediaKey: z.string().max(4096).nullish(),
});

export async function messageRoutes(app: FastifyInstance): Promise<void> {
  app.addHook('preHandler', requireAuth);

  // ---- Read the transcript ------------------------------------------------

  app.get('/v1/conversations/:id/messages', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const q = parse(
      z.object({
        before: z.coerce.number().int().positive().optional(),
        after: z.coerce.number().int().min(0).optional(),
        limit: z.coerce.number().int().min(1).max(100).default(50),
      }),
      request.query,
    );

    const userId = currentUser(request);
    await assertMember(params.id, userId);

    // Sequence-based paging rather than offset: an offset shifts as new
    // messages arrive, which makes a paging client skip or repeat rows.
    const rows = q.after !== undefined
      ? await query<MessageRow>(
          `SELECT * FROM messages WHERE conversation_id = $1 AND seq > $2
           ORDER BY seq ASC LIMIT $3`,
          [params.id, q.after, q.limit],
        )
      : await query<MessageRow>(
          `SELECT * FROM messages WHERE conversation_id = $1 AND ($2::bigint IS NULL OR seq < $2)
           ORDER BY seq DESC LIMIT $3`,
          [params.id, q.before ?? null, q.limit],
        );

    const items = await Promise.all(rows.map((row) => shapeMessage(row, userId)));
    return {
      items,
      hasMore: rows.length === q.limit,
      nextCursor: rows.length > 0 ? String(rows[rows.length - 1]!.seq) : null,
    };
  });

  // ---- Send ---------------------------------------------------------------

  app.post('/v1/messages', async (request) => {
    const body = parse(
      z.object({
        id: idSchema,
        conversationId: idSchema,
        kind: z.string().max(32).default('TEXT'),
        body: z.string().max(200_000).default(''),
        isEncrypted: z.boolean().default(true),
        encryptionAlgorithm: z.string().max(64).nullish(),
        wrappedKeys: z.record(z.string().max(64), z.string().max(8192)).default({}),
        replyToId: idSchema.nullish(),
        forwardedFrom: idSchema.nullish(),
        mentions: z.array(idSchema).max(256).default([]),
        attachments: z.array(attachmentSchema).max(20).default([]),
        poll: z
          .object({
            question: z.string().trim().min(1).max(300),
            options: z.array(z.string().trim().min(1).max(120)).min(2).max(12),
            allowsMultipleAnswers: z.boolean().default(false),
            closesAt: z.number().int().positive().nullish(),
          })
          .nullish(),
        location: z
          .object({
            latitude: z.number().min(-90).max(90),
            longitude: z.number().min(-180).max(180),
            label: z.string().max(200).nullish(),
            liveUntil: z.number().int().positive().nullish(),
          })
          .nullish(),
        contact: z
          .object({
            displayName: z.string().max(120),
            username: z.string().max(64).nullish(),
            phoneNumbers: z.array(z.string().max(32)).max(5).default([]),
            userId: idSchema.nullish(),
          })
          .nullish(),
        expiresAt: z.number().int().positive().nullish(),
        scheduledFor: z.number().int().positive().nullish(),
      }),
      request.body,
    );

    const userId = currentUser(request);
    await assertMember(body.conversationId, userId);

    const conversation = await queryOne<{
      type: string;
      disappearing_after_ms: number | null;
    }>('SELECT type, disappearing_after_ms FROM conversations WHERE id = $1', [body.conversationId]);
    if (!conversation) throw notFound('Conversation');

    // Group send permission is enforced here, not just hidden in the client.
    if (conversation.type === 'GROUP') {
      const group = await queryOne<{ send_permission: string }>(
        'SELECT send_permission FROM groups WHERE conversation_id = $1',
        [body.conversationId],
      );
      if (group?.send_permission === 'ADMINS_ONLY') {
        const membership = await queryOne<{ role: string }>(
          'SELECT role FROM conversation_members WHERE conversation_id = $1 AND user_id = $2',
          [body.conversationId, userId],
        );
        if (!membership || membership.role === 'MEMBER') {
          throw forbidden('Only admins can send messages in this group');
        }
      }
    } else {
      const others = (await memberIds(body.conversationId)).filter((id) => id !== userId);
      for (const other of others) {
        if (await isBlockedEitherWay(userId, other)) {
          throw forbidden('You cannot message this person');
        }
      }
    }

    // Idempotent: the client generated this id, so a retry of a request whose
    // response was lost returns the existing message rather than duplicating.
    const existing = await queryOne<MessageRow>('SELECT * FROM messages WHERE id = $1', [body.id]);
    if (existing) return shapeMessage(existing, userId);

    const now = Date.now();
    const expiresAt =
      body.expiresAt ??
      (conversation.disappearing_after_ms ? now + conversation.disappearing_after_ms : null);

    const saved = await transaction(async (client) => {
      // Allocating the sequence inside the transaction, with the row locked by
      // the UPDATE, is what guarantees no two concurrent sends share a seq.
      const seqRow = await client.query<{ last_seq: number }>(
        'UPDATE conversations SET last_seq = last_seq + 1, updated_at = $2 WHERE id = $1 RETURNING last_seq',
        [body.conversationId, now],
      );
      const seq = seqRow.rows[0]?.last_seq;
      if (seq === undefined) throw notFound('Conversation');

      const inserted = await client.query<MessageRow>(
        `INSERT INTO messages
           (id, conversation_id, sender_id, seq, kind, body, is_encrypted, encryption_algorithm,
            reply_to_id, forwarded_from, mentions, metadata, expires_at, created_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
         RETURNING *`,
        [
          body.id,
          body.conversationId,
          userId,
          seq,
          body.kind,
          body.body,
          body.isEncrypted,
          body.encryptionAlgorithm ?? null,
          body.replyToId ?? null,
          body.forwardedFrom ?? null,
          body.mentions,
          body.location || body.contact
            ? JSON.stringify({ location: body.location ?? null, contact: body.contact ?? null })
            : null,
          expiresAt,
          now,
        ],
      );

      for (const [recipientId, wrappedKey] of Object.entries(body.wrappedKeys)) {
        await client.query(
          `INSERT INTO message_keys (message_id, user_id, wrapped_key) VALUES ($1, $2, $3)
           ON CONFLICT (message_id, user_id) DO UPDATE SET wrapped_key = $3`,
          [body.id, recipientId, wrappedKey],
        );
      }

      for (const attachment of body.attachments) {
        await client.query(
          `INSERT INTO attachments
             (id, message_id, kind, storage_key, file_name, mime_type, size_bytes,
              width, height, duration_ms, blur_hash, waveform, media_key, created_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
           ON CONFLICT (id) DO NOTHING`,
          [
            attachment.id,
            body.id,
            attachment.kind,
            attachment.url ?? '',
            attachment.fileName,
            attachment.mimeType,
            attachment.sizeBytes,
            attachment.width,
            attachment.height,
            attachment.durationMs,
            attachment.blurHash ?? null,
            attachment.waveform,
            attachment.mediaKey ?? null,
            now,
          ],
        );
      }

      if (body.poll) {
        const pollId = newId();
        await client.query(
          `INSERT INTO polls (id, message_id, question, allows_multiple_answers, closes_at)
           VALUES ($1, $2, $3, $4, $5)`,
          [pollId, body.id, body.poll.question, body.poll.allowsMultipleAnswers, body.poll.closesAt ?? null],
        );
        for (const [index, text] of body.poll.options.entries()) {
          await client.query(
            'INSERT INTO poll_options (id, poll_id, text, position) VALUES ($1, $2, $3, $4)',
            [newId(), pollId, text, index],
          );
        }
      }

      // The sender's own read pointer moves with their message, so their own
      // sends never show as unread to them.
      await client.query(
        'UPDATE conversation_members SET last_read_seq = $1 WHERE conversation_id = $2 AND user_id = $3',
        [seq, body.conversationId, userId],
      );

      return inserted.rows[0]!;
    });

    const shaped = await shapeMessage(saved, userId);
    const recipients = (await memberIds(body.conversationId)).filter((id) => id !== userId);

    // Each recipient gets their own copy of the wrapped key; sending everyone
    // the whole map would hand every member every other member's envelope.
    for (const recipientId of recipients) {
      hub.toUser(recipientId, {
        t: 'message',
        message: { ...shaped, wrappedKey: body.wrappedKeys[recipientId] ?? null },
      });
    }
    // Other devices of the sender need it too, for multi-device consistency.
    hub.toUser(userId, { t: 'message', message: shaped }, request.deviceId);

    // A delivery receipt for every recipient currently connected.
    const onlineRecipients = recipients.filter((id) => hub.isOnline(id));
    if (onlineRecipients.length > 0) {
      const at = Date.now();
      for (const recipientId of onlineRecipients) {
        await query(
          `INSERT INTO receipts (message_id, user_id, type, at) VALUES ($1, $2, 'DELIVERED', $3)
           ON CONFLICT (message_id, user_id, type) DO NOTHING`,
          [body.id, recipientId, at],
        );
      }
      hub.toUser(userId, {
        t: 'receipt',
        conversationId: body.conversationId,
        messageIds: [body.id],
        userId: onlineRecipients[0]!,
        type: 'DELIVERED',
        at,
      });
    }

    return shaped;
  });

  // ---- Edit and delete ----------------------------------------------------

  app.patch('/v1/messages/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const body = parse(z.object({ body: z.string().max(200_000) }), request.body);
    const userId = currentUser(request);

    const message = await queryOne<MessageRow>('SELECT * FROM messages WHERE id = $1', [params.id]);
    if (!message) throw notFound('Message');
    if (message.sender_id !== userId) throw forbidden('You can only edit your own messages');
    if (message.deleted_at !== null) throw badRequest('That message was deleted');

    const updated = await queryOne<MessageRow>(
      'UPDATE messages SET body = $1, edited_at = $2 WHERE id = $3 RETURNING *',
      [body.body, Date.now(), params.id],
    );
    if (!updated) throw notFound('Message');

    const shaped = await shapeMessage(updated, userId);
    hub.toUsers(await memberIds(message.conversation_id), { t: 'message.updated', message: shaped });
    return shaped;
  });

  app.post('/v1/messages/:id/delete', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const body = parse(z.object({ forEveryone: z.boolean().default(false) }), request.body);
    const userId = currentUser(request);

    const message = await queryOne<MessageRow>('SELECT * FROM messages WHERE id = $1', [params.id]);
    if (!message) throw notFound('Message');

    if (body.forEveryone && message.sender_id !== userId) {
      throw forbidden('You can only delete your own messages for everyone');
    }

    if (body.forEveryone) {
      await transaction(async (client) => {
        // The body is overwritten, not just flagged: "delete for everyone"
        // that leaves the plaintext in the database is not a deletion.
        await client.query(
          "UPDATE messages SET deleted_at = $1, body = '' WHERE id = $2",
          [Date.now(), params.id],
        );
        await client.query('DELETE FROM attachments WHERE message_id = $1', [params.id]);
        await client.query('DELETE FROM message_keys WHERE message_id = $1', [params.id]);
      });

      hub.toUsers(await memberIds(message.conversation_id), {
        t: 'message.deleted',
        messageId: params.id,
        conversationId: message.conversation_id,
        forEveryone: true,
      });
    }

    return { ok: true };
  });

  // ---- Reactions, stars, receipts, polls ---------------------------------

  app.post('/v1/messages/:id/reactions', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const body = parse(
      z.object({ emoji: z.string().min(1).max(16), remove: z.boolean().default(false) }),
      request.body,
    );
    const userId = currentUser(request);

    const message = await queryOne<{ conversation_id: string }>(
      'SELECT conversation_id FROM messages WHERE id = $1',
      [params.id],
    );
    if (!message) throw notFound('Message');
    await assertMember(message.conversation_id, userId);

    const at = Date.now();
    if (body.remove) {
      await query('DELETE FROM reactions WHERE message_id = $1 AND user_id = $2 AND emoji = $3', [
        params.id,
        userId,
        body.emoji,
      ]);
    } else {
      await query(
        `INSERT INTO reactions (message_id, user_id, emoji, created_at) VALUES ($1, $2, $3, $4)
         ON CONFLICT (message_id, user_id, emoji) DO NOTHING`,
        [params.id, userId, body.emoji, at],
      );
    }

    hub.toUsers(await memberIds(message.conversation_id), {
      t: 'reaction',
      messageId: params.id,
      conversationId: message.conversation_id,
      reaction: { emoji: body.emoji, userId, createdAt: at },
      removed: body.remove,
    });

    return { ok: true };
  });

  app.post('/v1/messages/:id/star', async (request) => {
    // Starring is a purely local, per-user bookmark; the server accepts the
    // call so the client's outbox has a uniform shape, but stores nothing.
    parse(z.object({ id: idSchema }), request.params);
    return { ok: true };
  });

  app.post('/v1/messages/:id/vote', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const body = parse(z.object({ optionIds: z.array(idSchema).max(12) }), request.body);
    const userId = currentUser(request);

    const poll = await queryOne<{ id: string; allows_multiple_answers: boolean; is_closed: boolean; message_id: string }>(
      'SELECT id, allows_multiple_answers, is_closed, message_id FROM polls WHERE message_id = $1',
      [params.id],
    );
    if (!poll) throw notFound('Poll');
    if (poll.is_closed) throw badRequest('That poll is closed');
    if (!poll.allows_multiple_answers && body.optionIds.length > 1) {
      throw badRequest('That poll only accepts one answer');
    }

    const message = await queryOne<MessageRow>('SELECT * FROM messages WHERE id = $1', [params.id]);
    if (!message) throw notFound('Message');
    await assertMember(message.conversation_id, userId);

    await transaction(async (client) => {
      await client.query('DELETE FROM poll_votes WHERE poll_id = $1 AND user_id = $2', [poll.id, userId]);
      for (const optionId of body.optionIds) {
        await client.query(
          `INSERT INTO poll_votes (option_id, poll_id, user_id, at) VALUES ($1, $2, $3, $4)
           ON CONFLICT (option_id, user_id) DO NOTHING`,
          [optionId, poll.id, userId, Date.now()],
        );
      }
    });

    const shaped = await shapeMessage(message, userId);
    hub.toUsers(await memberIds(message.conversation_id), { t: 'message.updated', message: shaped });
    return shaped;
  });

  app.post('/v1/receipts/read', async (request) => {
    const body = parse(
      z.object({ conversationId: idSchema, upToMessageId: idSchema }),
      request.body,
    );
    const userId = currentUser(request);
    await assertMember(body.conversationId, userId);

    const target = await queryOne<{ seq: number }>(
      'SELECT seq FROM messages WHERE id = $1 AND conversation_id = $2',
      [body.upToMessageId, body.conversationId],
    );
    if (!target) throw notFound('Message');

    // GREATEST means an out-of-order receipt cannot move the pointer backwards.
    await query(
      `UPDATE conversation_members SET last_read_seq = GREATEST(last_read_seq, $1)
       WHERE conversation_id = $2 AND user_id = $3`,
      [target.seq, body.conversationId, userId],
    );

    // Read receipts are reciprocal: a user who has turned them off does not
    // emit them, which is what makes the setting honest rather than cosmetic.
    const privacy = await privacyFor(userId);
    if (!privacy.read_receipts) return { ok: true };

    const at = Date.now();
    const unread = await query<{ id: string; sender_id: string | null }>(
      `SELECT id, sender_id FROM messages
       WHERE conversation_id = $1 AND seq <= $2 AND sender_id <> $3
       ORDER BY seq DESC LIMIT 200`,
      [body.conversationId, target.seq, userId],
    );

    for (const message of unread) {
      await query(
        `INSERT INTO receipts (message_id, user_id, type, at) VALUES ($1, $2, 'READ', $3)
         ON CONFLICT (message_id, user_id, type) DO NOTHING`,
        [message.id, userId, at],
      );
    }

    const senders = new Set(unread.map((m) => m.sender_id).filter((id): id is string => id !== null));
    hub.toUsers(senders, {
      t: 'receipt',
      conversationId: body.conversationId,
      messageIds: unread.map((m) => m.id),
      userId,
      type: 'READ',
      at,
    });

    return { ok: true };
  });
}

export interface MessageRow {
  id: string;
  conversation_id: string;
  sender_id: string | null;
  seq: number;
  kind: string;
  body: string;
  is_encrypted: boolean;
  encryption_algorithm: string | null;
  reply_to_id: string | null;
  forwarded_from: string | null;
  mentions: string[];
  metadata: { location?: unknown; contact?: unknown } | null;
  expires_at: number | null;
  edited_at: number | null;
  deleted_at: number | null;
  created_at: number;
}

export async function shapeMessage(row: MessageRow, viewerId: string) {
  const [attachments, reactions, receipts, poll, wrappedKey] = await Promise.all([
    query<{
      id: string;
      kind: string;
      storage_key: string;
      file_name: string;
      mime_type: string;
      size_bytes: number;
      width: number;
      height: number;
      duration_ms: number;
      blur_hash: string | null;
      waveform: number[];
      media_key: string | null;
    }>('SELECT * FROM attachments WHERE message_id = $1', [row.id]),
    query<{ emoji: string; user_id: string; created_at: number }>(
      'SELECT emoji, user_id, created_at FROM reactions WHERE message_id = $1',
      [row.id],
    ),
    query<{ user_id: string; type: string; at: number }>(
      'SELECT user_id, type, at FROM receipts WHERE message_id = $1',
      [row.id],
    ),
    queryOne<{ id: string; question: string; allows_multiple_answers: boolean; closes_at: number | null; is_closed: boolean }>(
      'SELECT * FROM polls WHERE message_id = $1',
      [row.id],
    ),
    queryOne<{ wrapped_key: string }>(
      'SELECT wrapped_key FROM message_keys WHERE message_id = $1 AND user_id = $2',
      [row.id, viewerId],
    ),
  ]);

  let pollShape: {
    id: string;
    question: string;
    allowsMultipleAnswers: boolean;
    closesAt: number | null;
    isClosed: boolean;
    options: Array<{ id: string; text: string; voterIds: string[] }>;
  } | null = null;

  if (poll) {
    // Options and their votes in one query rather than one per option: a poll
    // with a dozen choices would otherwise cost a dozen round trips per
    // message, on every transcript page load.
    const optionRows = await query<{ id: string; text: string; voter_ids: string[] }>(
      `SELECT o.id, o.text,
              COALESCE(array_agg(v.user_id) FILTER (WHERE v.user_id IS NOT NULL), '{}') AS voter_ids
       FROM poll_options o
       LEFT JOIN poll_votes v ON v.option_id = o.id
       WHERE o.poll_id = $1
       GROUP BY o.id, o.text, o.position
       ORDER BY o.position`,
      [poll.id],
    );

    pollShape = {
      id: poll.id,
      question: poll.question,
      allowsMultipleAnswers: poll.allows_multiple_answers,
      closesAt: poll.closes_at,
      isClosed: poll.is_closed,
      options: optionRows.map((option) => ({
        id: option.id,
        text: option.text,
        voterIds: option.voter_ids,
      })),
    };
  }

  return {
    id: row.id,
    conversationId: row.conversation_id,
    senderId: row.sender_id ?? '',
    kind: row.kind,
    body: row.deleted_at !== null ? '' : row.body,
    isEncrypted: row.is_encrypted,
    encryptionAlgorithm: row.encryption_algorithm,
    wrappedKey: wrappedKey?.wrapped_key ?? null,
    createdAt: row.created_at,
    editedAt: row.edited_at,
    serverSeq: row.seq,
    replyToId: row.reply_to_id,
    forwardedFrom: row.forwarded_from,
    mentions: row.mentions,
    expiresAt: row.expires_at,
    isDeleted: row.deleted_at !== null,
    attachments: attachments.map((a) => ({
      id: a.id,
      kind: a.kind,
      url: a.storage_key || null,
      fileName: a.file_name,
      mimeType: a.mime_type,
      sizeBytes: a.size_bytes,
      width: a.width,
      height: a.height,
      durationMs: a.duration_ms,
      blurHash: a.blur_hash,
      waveform: a.waveform,
      mediaKey: a.media_key,
    })),
    reactions: reactions.map((r) => ({ emoji: r.emoji, userId: r.user_id, createdAt: r.created_at })),
    poll: pollShape,
    location: row.metadata?.location ?? null,
    contact: row.metadata?.contact ?? null,
    deliveredTo: receipts.filter((r) => r.type === 'DELIVERED').map((r) => ({ userId: r.user_id, at: r.at })),
    readBy: receipts.filter((r) => r.type === 'READ').map((r) => ({ userId: r.user_id, at: r.at })),
  };
}
