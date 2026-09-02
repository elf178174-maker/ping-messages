import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { query, queryOne } from '../db/pool.js';
import { currentUser, requireAuth } from '../lib/auth.js';
import { forbidden, notFound } from '../lib/errors.js';
import { newId } from '../lib/ids.js';
import { canView, privacyFor } from '../lib/presence.js';
import { audienceSchema, idSchema, parse } from '../lib/validation.js';
import { hub } from '../realtime/hub.js';

/** Status updates live for 24 hours, matching what the client's UI promises. */
const STATUS_TTL_MS = 24 * 60 * 60 * 1000;

export async function statusRoutes(app: FastifyInstance): Promise<void> {
  app.addHook('preHandler', requireAuth);

  /**
   * Every status the viewer is allowed to see.
   *
   * Audience filtering happens in SQL rather than after fetching: pulling
   * every status and filtering in JavaScript would mean the server briefly
   * held posts the viewer has no right to, and would not scale.
   */
  app.get('/v1/status', async (request) => {
    const viewerId = currentUser(request);
    const now = Date.now();

    const rows = await query<StatusRow>(
      `SELECT s.*, u.display_name, u.avatar_url,
              (SELECT COUNT(*) FROM status_views v WHERE v.status_id = s.id) AS view_count,
              EXISTS (SELECT 1 FROM status_views v WHERE v.status_id = s.id AND v.user_id = $1) AS seen_by_me
       FROM status_posts s
       JOIN users u ON u.id = s.author_id
       WHERE s.expires_at > $2
         AND u.is_deleted = FALSE
         AND NOT ($1 = ANY(s.excluded_ids))
         AND NOT EXISTS (
           SELECT 1 FROM blocks b
           WHERE (b.user_id = s.author_id AND b.blocked_id = $1)
              OR (b.user_id = $1 AND b.blocked_id = s.author_id)
         )
         AND (
           s.author_id = $1
           OR s.audience = 'EVERYONE'
           OR (s.audience = 'CONTACTS'
               AND EXISTS (SELECT 1 FROM contacts c
                           WHERE c.user_id = s.author_id AND c.contact_id = $1))
         )
       ORDER BY s.created_at ASC
       LIMIT 500`,
      [viewerId, now],
    );

    return Promise.all(rows.map((row) => shapeStatus(row, viewerId)));
  });

  app.post('/v1/status', async (request) => {
    const body = parse(
      z.object({
        kind: z.enum(['TEXT', 'IMAGE', 'VIDEO']).default('TEXT'),
        text: z.string().trim().max(700).default(''),
        mediaUrl: z.string().max(2048).nullish(),
        backgroundColor: z.number().int().nullish(),
        durationMs: z.number().int().min(1000).max(30_000).default(5000),
        audience: audienceSchema.default('CONTACTS'),
        excludedUserIds: z.array(idSchema).max(500).default([]),
      }),
      request.body,
    );

    if (body.kind === 'TEXT' && body.text.trim().length === 0) {
      throw forbidden('A text status needs some text');
    }
    if (body.kind !== 'TEXT' && !body.mediaUrl) {
      throw forbidden('A photo or video status needs media');
    }

    const userId = currentUser(request);
    const now = Date.now();
    const id = newId();

    await query(
      `INSERT INTO status_posts
         (id, author_id, kind, text, media_url, background_color, duration_ms,
          audience, excluded_ids, created_at, expires_at)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`,
      [
        id,
        userId,
        body.kind,
        body.text,
        body.mediaUrl ?? null,
        body.backgroundColor ?? null,
        body.durationMs,
        body.audience,
        body.excludedUserIds,
        now,
        now + STATUS_TTL_MS,
      ],
    );

    const row = await queryOne<StatusRow>(
      `SELECT s.*, u.display_name, u.avatar_url, 0::bigint AS view_count, FALSE AS seen_by_me
       FROM status_posts s JOIN users u ON u.id = s.author_id WHERE s.id = $1`,
      [id],
    );
    if (!row) throw notFound('Status');

    const shaped = await shapeStatus(row, userId);

    // Only the audience is notified. Computing that here rather than letting
    // clients filter is what makes "Status privacy" actually private.
    const audience = await query<{ user_id: string }>(
      body.audience === 'EVERYONE'
        ? 'SELECT id AS user_id FROM users WHERE is_deleted = FALSE AND id <> $1 LIMIT 5000'
        : 'SELECT contact_id AS user_id FROM contacts WHERE user_id = $1',
      [userId],
    );
    const excluded = new Set(body.excludedUserIds);
    hub.toUsers(
      audience.map((r) => r.user_id).filter((candidate) => !excluded.has(candidate)),
      { t: 'status', status: shaped },
    );

    return shaped;
  });

  app.delete('/v1/status/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const userId = currentUser(request);

    const row = await queryOne<{ author_id: string }>(
      'SELECT author_id FROM status_posts WHERE id = $1',
      [params.id],
    );
    if (!row) throw notFound('Status');
    if (row.author_id !== userId) throw forbidden('You can only delete your own status');

    await query('DELETE FROM status_posts WHERE id = $1', [params.id]);
    hub.toUsers([userId], { t: 'status.deleted', statusId: params.id });
    return { ok: true };
  });

  app.post('/v1/status/:id/view', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const viewerId = currentUser(request);

    const row = await queryOne<{ author_id: string; audience: string; excluded_ids: string[] }>(
      'SELECT author_id, audience, excluded_ids FROM status_posts WHERE id = $1 AND expires_at > $2',
      [params.id, Date.now()],
    );
    if (!row) throw notFound('Status');
    if (row.excluded_ids.includes(viewerId)) throw notFound('Status');

    const privacy = await privacyFor(row.author_id);
    if (!(await canView(row.author_id, viewerId, privacy.status))) throw notFound('Status');

    // A viewer with read receipts off does not record a view, which keeps the
    // setting consistent between messages and status.
    const viewerPrivacy = await privacyFor(viewerId);
    if (!viewerPrivacy.read_receipts) return { ok: true };

    await query(
      `INSERT INTO status_views (status_id, user_id, at) VALUES ($1, $2, $3)
       ON CONFLICT (status_id, user_id) DO NOTHING`,
      [params.id, viewerId, Date.now()],
    );

    return { ok: true };
  });
}

interface StatusRow {
  id: string;
  author_id: string;
  kind: string;
  text: string;
  media_url: string | null;
  background_color: number | null;
  duration_ms: number;
  created_at: number;
  expires_at: number;
  display_name: string;
  avatar_url: string | null;
  view_count: number;
  seen_by_me: boolean;
}

async function shapeStatus(row: StatusRow, viewerId: string) {
  // Viewers are only revealed to the author — seeing who viewed someone else's
  // status is not something any participant consented to.
  const viewers =
    row.author_id === viewerId
      ? await query<{ user_id: string; display_name: string; at: number }>(
          `SELECT v.user_id, u.display_name, v.at FROM status_views v
           JOIN users u ON u.id = v.user_id
           WHERE v.status_id = $1 ORDER BY v.at DESC`,
          [row.id],
        )
      : [];

  return {
    id: row.id,
    authorId: row.author_id,
    authorName: row.display_name,
    authorAvatarUrl: row.avatar_url,
    kind: row.kind,
    text: row.text,
    mediaUrl: row.media_url,
    backgroundColor: row.background_color,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    durationMs: row.duration_ms,
    seenByMe: row.seen_by_me,
    viewers: viewers.map((v) => ({ userId: v.user_id, userName: v.display_name, at: v.at })),
  };
}
