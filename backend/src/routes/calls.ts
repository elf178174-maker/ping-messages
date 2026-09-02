import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { callingEnabled, iceServers } from '../config.js';
import { query, queryOne, transaction } from '../db/pool.js';
import { assertMember, currentUser, isBlockedEitherWay, requireAuth } from '../lib/auth.js';
import { forbidden, notFound } from '../lib/errors.js';
import { newId } from '../lib/ids.js';
import { canView, privacyFor } from '../lib/presence.js';
import { idSchema, parse } from '../lib/validation.js';
import { hub } from '../realtime/hub.js';

export async function callRoutes(app: FastifyInstance): Promise<void> {
  app.addHook('preHandler', requireAuth);

  /**
   * ICE configuration.
   *
   * `enabled` is honest about whether calling can actually work: WebRTC needs
   * at least a STUN server to discover a route, so with none configured the
   * client shows an explanation instead of a call button that would ring
   * forever.
   */
  app.get('/v1/calls/config', async () => ({
    iceServers: iceServers(),
    enabled: callingEnabled(),
  }));

  app.post('/v1/calls', async (request) => {
    const body = parse(
      z.object({
        conversationId: idSchema,
        isVideo: z.boolean().default(false),
        calleeIds: z.array(idSchema).min(1).max(32),
      }),
      request.body,
    );

    if (!callingEnabled()) {
      throw forbidden('Calling is not configured on this server');
    }

    const userId = currentUser(request);
    await assertMember(body.conversationId, userId);

    const conversation = await queryOne<{ type: string }>(
      'SELECT type FROM conversations WHERE id = $1',
      [body.conversationId],
    );
    if (!conversation) throw notFound('Conversation');

    // "Who can call me" is checked per callee, and blocks are checked both
    // ways — a blocked user must not be able to make someone's phone ring.
    const reachable: string[] = [];
    for (const calleeId of new Set(body.calleeIds)) {
      if (calleeId === userId) continue;
      if (await isBlockedEitherWay(userId, calleeId)) continue;

      const privacy = await privacyFor(calleeId);
      if (!(await canView(calleeId, userId, privacy.calls))) continue;
      reachable.push(calleeId);
    }

    if (reachable.length === 0) {
      throw forbidden('That person is not accepting calls from you');
    }

    const callId = newId();
    const now = Date.now();
    const isGroup = conversation.type === 'GROUP' || reachable.length > 1;

    await transaction(async (client) => {
      await client.query(
        `INSERT INTO calls (id, conversation_id, initiator_id, is_video, is_group, outcome, started_at)
         VALUES ($1, $2, $3, $4, $5, 'ONGOING', $6)`,
        [callId, body.conversationId, userId, body.isVideo, isGroup, now],
      );
      await client.query(
        'INSERT INTO call_participants (call_id, user_id, joined_at) VALUES ($1, $2, $3)',
        [callId, userId, now],
      );
      for (const calleeId of reachable) {
        await client.query(
          'INSERT INTO call_participants (call_id, user_id) VALUES ($1, $2)',
          [callId, calleeId],
        );
      }
    });

    const caller = await queryOne<{ display_name: string; avatar_url: string | null }>(
      'SELECT display_name, avatar_url FROM users WHERE id = $1',
      [userId],
    );

    // The invite is pushed over the socket; SDP and ICE follow directly
    // between the peers. The server is a rendezvous point, never a media path.
    hub.toUsers(reachable, {
      t: 'call.invite',
      callId,
      conversationId: body.conversationId,
      fromUserId: userId,
      fromName: caller?.display_name ?? '',
      fromAvatarUrl: caller?.avatar_url ?? null,
      isVideo: body.isVideo,
      isGroup,
    });

    return {
      callId,
      conversationId: body.conversationId,
      isVideo: body.isVideo,
      participants: [userId, ...reachable],
      startedAt: now,
    };
  });

  app.post('/v1/calls/:id/end', async (request) => {
    const params = parse(z.object({ id: idSchema }), request.params);
    const q = parse(
      z.object({ duration: z.coerce.number().int().min(0).default(0) }),
      request.query,
    );
    const userId = currentUser(request);

    const call = await queryOne<{ conversation_id: string; outcome: string }>(
      'SELECT conversation_id, outcome FROM calls WHERE id = $1',
      [params.id],
    );
    if (!call) throw notFound('Call');
    await assertMember(call.conversation_id, userId);

    const now = Date.now();
    // A call that never connected is a missed call, not a zero-second
    // completed one — the distinction is what the history list shows.
    const outcome = q.duration > 0 ? 'COMPLETED' : 'MISSED';

    await transaction(async (client) => {
      await client.query(
        `UPDATE calls SET outcome = $1, ended_at = $2, duration_seconds = $3
         WHERE id = $4 AND outcome = 'ONGOING'`,
        [outcome, now, q.duration, params.id],
      );
      await client.query(
        'UPDATE call_participants SET left_at = $1 WHERE call_id = $2 AND user_id = $3',
        [now, params.id, userId],
      );
    });

    const participants = await query<{ user_id: string }>(
      'SELECT user_id FROM call_participants WHERE call_id = $1',
      [params.id],
    );
    hub.toUsers(
      participants.map((p) => p.user_id).filter((id) => id !== userId),
      { t: 'call.hangup', callId: params.id, fromUserId: userId, reason: 'ended' },
    );

    return { ok: true };
  });

  app.get('/v1/calls', async (request) => {
    const q = parse(
      z.object({ limit: z.coerce.number().int().min(1).max(200).default(100) }),
      request.query,
    );
    const userId = currentUser(request);

    const rows = await query<{
      id: string;
      conversation_id: string;
      initiator_id: string | null;
      is_video: boolean;
      is_group: boolean;
      outcome: string;
      started_at: number;
      duration_seconds: number;
      peer_id: string | null;
      peer_name: string | null;
      peer_avatar: string | null;
    }>(
      `SELECT c.id, c.conversation_id, c.initiator_id, c.is_video, c.is_group, c.outcome,
              c.started_at, c.duration_seconds,
              peer.id AS peer_id, peer.display_name AS peer_name, peer.avatar_url AS peer_avatar
       FROM calls c
       JOIN call_participants me ON me.call_id = c.id AND me.user_id = $1
       LEFT JOIN LATERAL (
         SELECT u.id, u.display_name, u.avatar_url
         FROM call_participants p JOIN users u ON u.id = p.user_id
         WHERE p.call_id = c.id AND p.user_id <> $1
         LIMIT 1
       ) peer ON TRUE
       ORDER BY c.started_at DESC
       LIMIT $2`,
      [userId, q.limit],
    );

    return rows.map((row) => ({
      id: row.id,
      conversationId: row.conversation_id,
      peerId: row.peer_id ?? '',
      peerName: row.peer_name ?? '',
      peerAvatarUrl: row.peer_avatar,
      isVideo: row.is_video,
      isGroup: row.is_group,
      direction: row.initiator_id === userId ? 'OUTGOING' : 'INCOMING',
      outcome: row.outcome,
      startedAt: row.started_at,
      durationSeconds: row.duration_seconds,
      participantNames: [],
    }));
  });

  app.delete('/v1/calls', async (request) => {
    // Clears this user's view of the history without destroying the other
    // party's copy, which is theirs.
    await query('DELETE FROM call_participants WHERE user_id = $1', [currentUser(request)]);
    return { ok: true };
  });
}
