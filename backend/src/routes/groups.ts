import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { config } from '../config.js';
import { query, queryOne, transaction } from '../db/pool.js';
import { assertAdmin, assertMember, currentUser, requireAuth } from '../lib/auth.js';
import { badRequest, forbidden, notFound } from '../lib/errors.js';
import { newId, newInviteCode } from '../lib/ids.js';
import { canView, privacyFor } from '../lib/presence.js';
import { displayNameSchema, idSchema, parse, permissionSchema, roleSchema } from '../lib/validation.js';
import { hub } from '../realtime/hub.js';
import { memberIds } from './conversations.js';

const MAX_MEMBERS = 1024;

export async function groupRoutes(app: FastifyInstance): Promise<void> {
  app.addHook('preHandler', requireAuth);

  app.post('/v1/groups', async (request) => {
    const body = parse(
      z.object({
        name: z.string().trim().min(1).max(64),
        description: z.string().trim().max(280).default(''),
        memberIds: z.array(idSchema).min(1).max(MAX_MEMBERS - 1),
        avatarUrl: z.string().max(2048).nullish(),
      }),
      request.body,
    );

    const userId = currentUser(request);
    const now = Date.now();
    const conversationId = newId();
    const groupId = newId();

    // "Who can add me to groups" is enforced at creation, not just on later
    // adds — otherwise the setting is trivially bypassed by making a new group.
    const allowed: string[] = [];
    for (const candidate of new Set(body.memberIds)) {
      if (candidate === userId) continue;
      const exists = await queryOne('SELECT 1 FROM users WHERE id = $1 AND is_deleted = FALSE', [candidate]);
      if (!exists) continue;

      const privacy = await privacyFor(candidate);
      if (await canView(candidate, userId, privacy.groups)) allowed.push(candidate);
    }

    if (allowed.length === 0) {
      throw badRequest('None of those people allow you to add them to a group');
    }

    await transaction(async (client) => {
      await client.query(
        'INSERT INTO conversations (id, type, created_at, updated_at) VALUES ($1, $2, $3, $3)',
        [conversationId, 'GROUP', now],
      );
      await client.query(
        `INSERT INTO groups
           (id, conversation_id, name, description, avatar_url, created_by, invite_code, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8)`,
        [groupId, conversationId, body.name, body.description, body.avatarUrl ?? null, userId, newInviteCode(), now],
      );
      await client.query(
        `INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
         VALUES ($1, $2, 'OWNER', $3)`,
        [conversationId, userId, now],
      );
      for (const memberId of allowed) {
        await client.query(
          `INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
           VALUES ($1, $2, 'MEMBER', $3)`,
          [conversationId, memberId, now],
        );
      }
    });

    const group = await loadGroup(groupId, userId);
    hub.toUsers([userId, ...allowed], { t: 'group', group });
    return group;
  });

  app.get('/v1/groups/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const userId = currentUser(request);
    const group = await loadGroup(params.id, userId);
    await assertMember(group.conversationId, userId);
    return group;
  });

  app.patch('/v1/groups/:id', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const body = parse(
      z.object({
        name: z.string().trim().min(1).max(64).optional(),
        description: z.string().trim().max(280).optional(),
        avatarUrl: z.string().max(2048).nullish(),
        sendPermission: permissionSchema.optional(),
        editInfoPermission: permissionSchema.optional(),
        addMembersPermission: permissionSchema.optional(),
      }),
      request.body,
    );

    const userId = currentUser(request);
    const row = await queryOne<{ conversation_id: string; edit_info_permission: string }>(
      'SELECT conversation_id, edit_info_permission FROM groups WHERE id = $1',
      [params.id],
    );
    if (!row) throw notFound('Group');

    const changesPermissions =
      body.sendPermission || body.editInfoPermission || body.addMembersPermission;

    // Editing info can be open to everyone; changing the permissions
    // themselves is always admin-only, or a member could grant themselves
    // rights the admins withheld.
    if (changesPermissions || row.edit_info_permission === 'ADMINS_ONLY') {
      await assertAdmin(row.conversation_id, userId);
    } else {
      await assertMember(row.conversation_id, userId);
    }

    await query(
      `UPDATE groups SET
         name = COALESCE($2, name),
         description = COALESCE($3, description),
         avatar_url = COALESCE($4, avatar_url),
         send_permission = COALESCE($5, send_permission),
         edit_info_permission = COALESCE($6, edit_info_permission),
         add_members_permission = COALESCE($7, add_members_permission),
         updated_at = $8
       WHERE id = $1`,
      [
        params.id,
        body.name ?? null,
        body.description ?? null,
        body.avatarUrl ?? null,
        body.sendPermission ?? null,
        body.editInfoPermission ?? null,
        body.addMembersPermission ?? null,
        Date.now(),
      ],
    );

    const group = await loadGroup(params.id, userId);
    hub.toUsers(await memberIds(row.conversation_id), { t: 'group', group });
    return group;
  });

  app.post('/v1/groups/:id/members', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const body = parse(z.object({ userIds: z.array(idSchema).min(1).max(256) }), request.body);

    const userId = currentUser(request);
    const row = await queryOne<{ conversation_id: string; add_members_permission: string }>(
      'SELECT conversation_id, add_members_permission FROM groups WHERE id = $1',
      [params.id],
    );
    if (!row) throw notFound('Group');

    if (row.add_members_permission === 'ADMINS_ONLY') {
      await assertAdmin(row.conversation_id, userId);
    } else {
      await assertMember(row.conversation_id, userId);
    }

    const currentCount = (await memberIds(row.conversation_id)).length;
    if (currentCount + body.userIds.length > MAX_MEMBERS) {
      throw badRequest(`A group cannot exceed ${MAX_MEMBERS} members`);
    }

    const now = Date.now();
    const added: string[] = [];
    for (const candidate of new Set(body.userIds)) {
      const exists = await queryOne('SELECT 1 FROM users WHERE id = $1 AND is_deleted = FALSE', [candidate]);
      if (!exists) continue;

      const privacy = await privacyFor(candidate);
      if (!(await canView(candidate, userId, privacy.groups))) continue;

      await query(
        `INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
         VALUES ($1, $2, 'MEMBER', $3)
         ON CONFLICT (conversation_id, user_id) DO UPDATE SET left_at = NULL`,
        [row.conversation_id, candidate, now],
      );
      added.push(candidate);
    }

    const group = await loadGroup(params.id, userId);
    hub.toUsers(await memberIds(row.conversation_id), { t: 'group', group });
    return group;
  });

  app.delete('/v1/groups/:id/members/:userId', async (request) => {
    const params = parse(z.object({ id: idSchema, userId: idSchema }), request.params);
    const actorId = currentUser(request);

    const row = await queryOne<{ conversation_id: string }>(
      'SELECT conversation_id FROM groups WHERE id = $1',
      [params.id],
    );
    if (!row) throw notFound('Group');
    await assertAdmin(row.conversation_id, actorId);

    const target = await queryOne<{ role: string }>(
      'SELECT role FROM conversation_members WHERE conversation_id = $1 AND user_id = $2',
      [row.conversation_id, params.userId],
    );
    if (target?.role === 'OWNER') throw forbidden('The group owner cannot be removed');

    await query(
      'UPDATE conversation_members SET left_at = $1 WHERE conversation_id = $2 AND user_id = $3',
      [Date.now(), row.conversation_id, params.userId],
    );

    const group = await loadGroup(params.id, actorId);
    hub.toUsers([...(await memberIds(row.conversation_id)), params.userId], { t: 'group', group });
    return group;
  });

  app.put('/v1/groups/:id/members/:userId/role', async (request) => {
    const params = parse(z.object({ id: idSchema, userId: idSchema }), request.params);
    const body = parse(z.object({ role: roleSchema }), request.body);
    const actorId = currentUser(request);

    const row = await queryOne<{ conversation_id: string }>(
      'SELECT conversation_id FROM groups WHERE id = $1',
      [params.id],
    );
    if (!row) throw notFound('Group');
    await assertAdmin(row.conversation_id, actorId);

    // Ownership transfer is a separate, deliberate operation; letting any
    // admin grant OWNER would make the "owner cannot be removed" rule useless.
    if (body.role === 'OWNER') throw forbidden('Ownership cannot be granted this way');

    await query(
      `UPDATE conversation_members SET role = $1
       WHERE conversation_id = $2 AND user_id = $3 AND role <> 'OWNER'`,
      [body.role, row.conversation_id, params.userId],
    );

    const group = await loadGroup(params.id, actorId);
    hub.toUsers(await memberIds(row.conversation_id), { t: 'group', group });
    return group;
  });

  app.post('/v1/groups/:id/leave', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const userId = currentUser(request);

    const row = await queryOne<{ conversation_id: string }>(
      'SELECT conversation_id FROM groups WHERE id = $1',
      [params.id],
    );
    if (!row) throw notFound('Group');

    const membership = await queryOne<{ role: string }>(
      'SELECT role FROM conversation_members WHERE conversation_id = $1 AND user_id = $2',
      [row.conversation_id, userId],
    );

    await transaction(async (client) => {
      await client.query(
        'UPDATE conversation_members SET left_at = $1 WHERE conversation_id = $2 AND user_id = $3',
        [Date.now(), row.conversation_id, userId],
      );

      // An owner leaving hands ownership to the longest-serving admin, or the
      // longest-serving member — a group with no owner can never be
      // administered again.
      if (membership?.role === 'OWNER') {
        const successor = await client.query<{ user_id: string }>(
          `SELECT user_id FROM conversation_members
           WHERE conversation_id = $1 AND left_at IS NULL AND user_id <> $2
           ORDER BY CASE role WHEN 'ADMIN' THEN 0 ELSE 1 END, joined_at
           LIMIT 1`,
          [row.conversation_id, userId],
        );
        const heir = successor.rows[0]?.user_id;
        if (heir) {
          await client.query(
            "UPDATE conversation_members SET role = 'OWNER' WHERE conversation_id = $1 AND user_id = $2",
            [row.conversation_id, heir],
          );
        }
      }
    });

    hub.toUsers(await memberIds(row.conversation_id), {
      t: 'group',
      group: await loadGroup(params.id, userId),
    });
    return { ok: true };
  });

  // ---- Invite links -------------------------------------------------------

  app.get('/v1/groups/:id/invite', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const userId = currentUser(request);

    const row = await queryOne<{ conversation_id: string; invite_code: string | null }>(
      'SELECT conversation_id, invite_code FROM groups WHERE id = $1',
      [params.id],
    );
    if (!row) throw notFound('Group');
    await assertAdmin(row.conversation_id, userId);

    let code = row.invite_code;
    if (!code) {
      code = newInviteCode();
      await query('UPDATE groups SET invite_code = $1 WHERE id = $2', [code, params.id]);
    }

    return { code, url: `${config.PUBLIC_BASE_URL}/join/${code}` };
  });

  app.post('/v1/groups/:id/invite/reset', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const userId = currentUser(request);

    const row = await queryOne<{ conversation_id: string }>(
      'SELECT conversation_id FROM groups WHERE id = $1',
      [params.id],
    );
    if (!row) throw notFound('Group');
    await assertAdmin(row.conversation_id, userId);

    const code = newInviteCode();
    await query('UPDATE groups SET invite_code = $1, updated_at = $2 WHERE id = $3', [
      code,
      Date.now(),
      params.id,
    ]);

    return { code, url: `${config.PUBLIC_BASE_URL}/join/${code}` };
  });

  app.post('/v1/groups/join/:code', async (request) => {
    const params = parse(z.object({ code: z.string().trim().min(6).max(32) }), request.params);
    const userId = currentUser(request);

    const row = await queryOne<{ id: string; conversation_id: string }>(
      'SELECT id, conversation_id FROM groups WHERE invite_code = $1',
      [params.code],
    );
    if (!row) throw notFound('Invite link');

    const count = (await memberIds(row.conversation_id)).length;
    if (count >= MAX_MEMBERS) throw badRequest('That group is full');

    await query(
      `INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
       VALUES ($1, $2, 'MEMBER', $3)
       ON CONFLICT (conversation_id, user_id) DO UPDATE SET left_at = NULL`,
      [row.conversation_id, userId, Date.now()],
    );

    const group = await loadGroup(row.id, userId);
    hub.toUsers(await memberIds(row.conversation_id), { t: 'group', group });
    return group;
  });
}

async function loadGroup(groupId: string, viewerId: string) {
  const group = await queryOne<{
    id: string;
    conversation_id: string;
    name: string;
    description: string;
    avatar_url: string | null;
    created_by: string | null;
    invite_code: string | null;
    send_permission: string;
    edit_info_permission: string;
    add_members_permission: string;
    created_at: number;
  }>('SELECT * FROM groups WHERE id = $1', [groupId]);
  if (!group) throw notFound('Group');

  const members = await query<{
    user_id: string;
    role: string;
    joined_at: number;
    username: string;
    display_name: string;
    avatar_url: string | null;
    public_key: string | null;
  }>(
    `SELECT m.user_id, m.role, m.joined_at, u.username, u.display_name, u.avatar_url,
            (SELECT public_key FROM devices d
             WHERE d.user_id = u.id AND d.revoked_at IS NULL AND d.public_key IS NOT NULL
             ORDER BY d.last_active_at DESC LIMIT 1) AS public_key
     FROM conversation_members m
     JOIN users u ON u.id = m.user_id
     WHERE m.conversation_id = $1 AND m.left_at IS NULL
     ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, lower(u.display_name)`,
    [group.conversation_id],
  );

  const creator = group.created_by
    ? await queryOne<{ display_name: string }>('SELECT display_name FROM users WHERE id = $1', [
        group.created_by,
      ])
    : null;

  const myRole = members.find((m) => m.user_id === viewerId)?.role ?? 'MEMBER';

  return {
    id: group.id,
    conversationId: group.conversation_id,
    name: group.name,
    description: group.description,
    avatarUrl: group.avatar_url,
    createdAt: group.created_at,
    createdById: group.created_by ?? '',
    createdByName: creator?.display_name ?? '',
    // The invite code is only handed to admins; a member being able to read it
    // would defeat the admins-only permission on the invite screen.
    inviteCode: myRole === 'MEMBER' ? null : group.invite_code,
    sendPermission: group.send_permission,
    editInfoPermission: group.edit_info_permission,
    addMembersPermission: group.add_members_permission,
    myRole,
    members: members.map((m) => ({
      userId: m.user_id,
      username: m.username,
      displayName: m.display_name,
      avatarUrl: m.avatar_url,
      role: m.role,
      joinedAt: m.joined_at,
      publicKey: m.public_key,
    })),
  };
}
