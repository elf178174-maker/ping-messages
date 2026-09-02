import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { query, queryOne, transaction } from '../db/pool.js';
import { currentUser, currentDevice, requireAuth } from '../lib/auth.js';
import { conflict, forbidden, notFound } from '../lib/errors.js';
import { newId } from '../lib/ids.js';
import { canView, privacyFor, projectUser } from '../lib/presence.js';
import {
  aboutSchema,
  audienceSchema,
  displayNameSchema,
  idSchema,
  parse,
  usernameSchema,
} from '../lib/validation.js';

interface UserRow {
  id: string;
  username: string;
  display_name: string;
  about: string;
  avatar_url: string | null;
  updated_at: number;
}

const USER_COLUMNS = 'id, username, display_name, about, avatar_url, updated_at';

export async function userRoutes(app: FastifyInstance): Promise<void> {
  app.addHook('preHandler', requireAuth);

  // ---- Me -----------------------------------------------------------------

  app.get('/v1/me', async (request) => {
    const userId = currentUser(request);
    const row = await queryOne<UserRow & { phone_number: string | null }>(
      `SELECT ${USER_COLUMNS}, phone_number FROM users WHERE id = $1 AND is_deleted = FALSE`,
      [userId],
    );
    if (!row) throw notFound('User');

    // The owner sees their own unredacted record, including their phone number.
    return { ...(await projectUser(row, userId)), phoneNumber: row.phone_number };
  });

  app.patch('/v1/me', async (request) => {
    const body = parse(
      z.object({
        displayName: displayNameSchema.optional(),
        about: aboutSchema.optional(),
        username: usernameSchema.optional(),
        avatarUrl: z.string().max(2048).nullish(),
      }),
      request.body,
    );

    const userId = currentUser(request);

    if (body.username) {
      const taken = await queryOne(
        'SELECT 1 FROM users WHERE lower(username) = $1 AND id <> $2 AND is_deleted = FALSE',
        [body.username, userId],
      );
      if (taken) {
        throw conflict('That username is already taken', { username: 'That username is taken' });
      }
    }

    // COALESCE keeps this a single statement for any subset of fields, instead
    // of building SQL dynamically from whichever keys happen to be present.
    const row = await queryOne<UserRow>(
      `UPDATE users SET
         display_name = COALESCE($2, display_name),
         about        = COALESCE($3, about),
         username     = COALESCE($4, username),
         avatar_url   = COALESCE($5, avatar_url),
         updated_at   = $6
       WHERE id = $1 AND is_deleted = FALSE
       RETURNING ${USER_COLUMNS}`,
      [
        userId,
        body.displayName ?? null,
        body.about ?? null,
        body.username ?? null,
        body.avatarUrl ?? null,
        Date.now(),
      ],
    );
    if (!row) throw notFound('User');

    return projectUser(row, userId);
  });

  /**
   * Account deletion.
   *
   * A soft delete on `users` plus a hard delete of the identifying columns:
   * the row has to survive so that foreign keys from messages other people
   * still hold do not break, but nothing identifying remains in it.
   */
  app.delete('/v1/me', async (request) => {
    const userId = currentUser(request);
    const now = Date.now();

    await transaction(async (client) => {
      await client.query(
        `UPDATE users SET
           is_deleted = TRUE,
           email = 'deleted-' || id || '@invalid',
           email_normalised = 'deleted-' || id || '@invalid',
           username = 'deleted_' || substr(id, 1, 12),
           display_name = 'Deleted account',
           about = '',
           avatar_url = NULL,
           phone_number = NULL,
           phone_hash = NULL,
           password_hash = '',
           two_step_pin_hash = NULL,
           updated_at = $2
         WHERE id = $1`,
        [userId, now],
      );
      await client.query('UPDATE devices SET revoked_at = $1, refresh_token_hash = NULL, public_key = NULL WHERE user_id = $2', [now, userId]);
      await client.query('DELETE FROM status_posts WHERE author_id = $1', [userId]);
      await client.query('DELETE FROM contacts WHERE user_id = $1 OR contact_id = $1', [userId]);
      await client.query('UPDATE conversation_members SET left_at = $1 WHERE user_id = $2 AND left_at IS NULL', [now, userId]);
    });

    return { ok: true };
  });

  // ---- Privacy ------------------------------------------------------------

  app.get('/v1/me/privacy', async (request) => {
    const privacy = await privacyFor(currentUser(request));
    return {
      lastSeen: privacy.last_seen,
      onlineStatus: privacy.online_status,
      profilePhoto: privacy.profile_photo,
      about: privacy.about,
      status: privacy.status,
      groups: privacy.groups,
      calls: privacy.calls,
      readReceipts: privacy.read_receipts,
      typingIndicators: privacy.typing_indicators,
    };
  });

  app.put('/v1/me/privacy', async (request) => {
    const body = parse(
      z.object({
        lastSeen: audienceSchema,
        onlineStatus: audienceSchema,
        profilePhoto: audienceSchema,
        about: audienceSchema,
        status: audienceSchema,
        groups: audienceSchema,
        calls: audienceSchema,
        readReceipts: z.boolean(),
        typingIndicators: z.boolean(),
      }),
      request.body,
    );

    await query(
      `INSERT INTO user_privacy
         (user_id, last_seen, online_status, profile_photo, about, status, groups, calls,
          read_receipts, typing_indicators)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
       ON CONFLICT (user_id) DO UPDATE SET
         last_seen = $2, online_status = $3, profile_photo = $4, about = $5, status = $6,
         groups = $7, calls = $8, read_receipts = $9, typing_indicators = $10`,
      [
        currentUser(request),
        body.lastSeen,
        body.onlineStatus,
        body.profilePhoto,
        body.about,
        body.status,
        body.groups,
        body.calls,
        body.readReceipts,
        body.typingIndicators,
      ],
    );

    return body;
  });

  // ---- Devices ------------------------------------------------------------

  app.get('/v1/me/devices', async (request) => {
    const userId = currentUser(request);
    const thisDevice = currentDevice(request);
    const rows = await query<{
      id: string;
      name: string;
      platform: string;
      last_active_at: number;
      created_at: number;
      ip_country: string | null;
      public_key: string | null;
    }>(
      `SELECT id, name, platform, last_active_at, created_at, ip_country, public_key
       FROM devices WHERE user_id = $1 AND revoked_at IS NULL
       ORDER BY last_active_at DESC`,
      [userId],
    );

    return rows.map((row) => ({
      id: row.id,
      name: row.name,
      platform: row.platform,
      lastActiveAt: row.last_active_at,
      createdAt: row.created_at,
      isCurrent: row.id === thisDevice,
      ipCountry: row.ip_country,
      publicKey: row.public_key,
    }));
  });

  app.delete('/v1/me/devices/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const userId = currentUser(request);

    if (params.id === currentDevice(request)) {
      throw forbidden('Use sign out to end the session on this device');
    }

    await query(
      'UPDATE devices SET revoked_at = $1, refresh_token_hash = NULL WHERE id = $2 AND user_id = $3',
      [Date.now(), params.id, userId],
    );
    return { ok: true };
  });

  app.delete('/v1/me/devices', async (request) => {
    await query(
      `UPDATE devices SET revoked_at = $1, refresh_token_hash = NULL
       WHERE user_id = $2 AND id <> $3 AND revoked_at IS NULL`,
      [Date.now(), currentUser(request), currentDevice(request)],
    );
    return { ok: true };
  });

  /**
   * Publishes this device's public key.
   *
   * The server stores and hands it out but never uses it: it cannot decrypt
   * anything with a public key, which is precisely the property that makes
   * end-to-end encryption meaningful.
   */
  app.put('/v1/me/device-key', async (request) => {
    const body = parse(z.object({ publicKey: z.string().min(1).max(4096) }), request.body);
    await query('UPDATE devices SET public_key = $1 WHERE id = $2', [
      body.publicKey,
      currentDevice(request),
    ]);
    return { ok: true };
  });

  // ---- Other users --------------------------------------------------------

  app.get('/v1/users/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const row = await queryOne<UserRow>(
      `SELECT ${USER_COLUMNS} FROM users WHERE id = $1 AND is_deleted = FALSE`,
      [params.id],
    );
    if (!row) throw notFound('User');
    return projectUser(row, currentUser(request));
  });

  app.get('/v1/users/by-username/:username', async (request) => {
    const params = parse(z.object({ username: usernameSchema }), request.params);
    const row = await queryOne<UserRow>(
      `SELECT ${USER_COLUMNS} FROM users WHERE lower(username) = $1 AND is_deleted = FALSE`,
      [params.username],
    );
    if (!row) throw notFound('User');
    return projectUser(row, currentUser(request));
  });

  app.get('/v1/users', async (request) => {
    const params = parse(
      z.object({ q: z.string().trim().min(2).max(64), limit: z.coerce.number().int().min(1).max(50).default(20) }),
      request.query,
    );
    const viewerId = currentUser(request);
    const needle = `%${params.q.replace(/[%_]/g, (m) => `\\${m}`)}%`;

    // Exact username matches rank first: someone typing a full handle wants
    // that person, not a display-name near-match.
    const rows = await query<UserRow>(
      `SELECT ${USER_COLUMNS} FROM users
       WHERE is_deleted = FALSE
         AND id <> $1
         AND (lower(username) = $2 OR lower(username) LIKE $3 OR lower(display_name) LIKE $3)
         AND NOT EXISTS (
           SELECT 1 FROM blocks
           WHERE (user_id = users.id AND blocked_id = $1) OR (user_id = $1 AND blocked_id = users.id)
         )
       ORDER BY (lower(username) = $2) DESC, lower(display_name)
       LIMIT $4`,
      [viewerId, params.q.toLowerCase(), needle.toLowerCase(), params.limit],
    );

    return Promise.all(rows.map((row) => projectUser(row, viewerId)));
  });

  // ---- Contacts -----------------------------------------------------------

  app.get('/v1/contacts', async (request) => {
    const viewerId = currentUser(request);
    const rows = await query<UserRow>(
      `SELECT u.${USER_COLUMNS.split(', ').join(', u.')} FROM contacts c
       JOIN users u ON u.id = c.contact_id
       WHERE c.user_id = $1 AND u.is_deleted = FALSE
       ORDER BY lower(u.display_name)`,
      [viewerId],
    );
    return Promise.all(rows.map((row) => projectUser(row, viewerId)));
  });

  app.post('/v1/contacts/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const viewerId = currentUser(request);
    if (params.id === viewerId) throw forbidden('You cannot add yourself');

    const exists = await queryOne('SELECT 1 FROM users WHERE id = $1 AND is_deleted = FALSE', [params.id]);
    if (!exists) throw notFound('User');

    await query(
      `INSERT INTO contacts (user_id, contact_id, created_at) VALUES ($1, $2, $3)
       ON CONFLICT (user_id, contact_id) DO NOTHING`,
      [viewerId, params.id, Date.now()],
    );
    return { ok: true };
  });

  app.delete('/v1/contacts/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    await query('DELETE FROM contacts WHERE user_id = $1 AND contact_id = $2', [
      currentUser(request),
      params.id,
    ]);
    return { ok: true };
  });

  /**
   * Contact discovery by salted phone hash.
   *
   * The client sends hashes, never numbers, so the server learns nothing about
   * an address book beyond which of its own users appear in it. A production
   * deployment should additionally rate-limit this hard — a large enough hash
   * batch is still a way to test membership of specific numbers.
   */
  app.post('/v1/contacts/discover', async (request) => {
    const body = parse(
      z.object({ hashes: z.array(z.string().length(64)).max(2000) }),
      request.body,
    );
    const viewerId = currentUser(request);
    if (body.hashes.length === 0) return { matches: {} };

    const rows = await query<UserRow & { phone_hash: string }>(
      `SELECT ${USER_COLUMNS}, phone_hash FROM users
       WHERE phone_hash = ANY($1) AND is_deleted = FALSE AND id <> $2`,
      [body.hashes, viewerId],
    );

    const matches: Record<string, unknown> = {};
    for (const row of rows) {
      matches[row.phone_hash] = await projectUser(row, viewerId);
    }
    return { matches };
  });

  // ---- Blocks and reports -------------------------------------------------

  app.get('/v1/blocks', async (request) => {
    const viewerId = currentUser(request);
    const rows = await query<UserRow>(
      `SELECT u.${USER_COLUMNS.split(', ').join(', u.')} FROM blocks b
       JOIN users u ON u.id = b.blocked_id
       WHERE b.user_id = $1
       ORDER BY lower(u.display_name)`,
      [viewerId],
    );
    return Promise.all(rows.map((row) => projectUser(row, viewerId)));
  });

  app.post('/v1/blocks', async (request) => {
    const body = parse(z.object({ userId: idSchema }), request.body);
    const viewerId = currentUser(request);
    if (body.userId === viewerId) throw forbidden('You cannot block yourself');

    await transaction(async (client) => {
      await client.query(
        `INSERT INTO blocks (user_id, blocked_id, created_at) VALUES ($1, $2, $3)
         ON CONFLICT (user_id, blocked_id) DO NOTHING`,
        [viewerId, body.userId, Date.now()],
      );
      // Blocking also removes them as a contact; leaving them in the list
      // while blocked is the kind of inconsistency that erodes trust in the
      // feature.
      await client.query('DELETE FROM contacts WHERE user_id = $1 AND contact_id = $2', [
        viewerId,
        body.userId,
      ]);
    });

    return { ok: true };
  });

  app.delete('/v1/blocks/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    await query('DELETE FROM blocks WHERE user_id = $1 AND blocked_id = $2', [
      currentUser(request),
      params.id,
    ]);
    return { ok: true };
  });

  app.post('/v1/reports', async (request) => {
    const body = parse(
      z.object({
        userId: idSchema,
        reason: z.string().trim().min(1).max(64),
        messageIds: z.array(idSchema).max(50).default([]),
        note: z.string().trim().max(2000).nullish(),
      }),
      request.body,
    );

    await query(
      `INSERT INTO reports (id, reporter_id, reported_id, reason, note, message_ids, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [
        newId(),
        currentUser(request),
        body.userId,
        body.reason,
        body.note ?? null,
        body.messageIds,
        Date.now(),
      ],
    );

    return { ok: true, message: 'Report submitted' };
  });
}
