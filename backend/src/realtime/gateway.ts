import '@fastify/websocket';
import type { WebSocket } from 'ws';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { query, queryOne } from '../db/pool.js';
import { isBlockedEitherWay } from '../lib/auth.js';
import { markOnline, privacyFor } from '../lib/presence.js';
import { verifyAccessToken } from '../lib/tokens.js';
import { hub } from './hub.js';

/**
 * The realtime WebSocket.
 *
 * Carries live events in both directions: new messages and receipts outbound,
 * typing and presence inbound, and WebRTC signalling both ways. One socket
 * rather than several keeps the device's radio asleep more of the time, which
 * is the dominant battery cost in a messaging app.
 *
 * Inbound frames are validated with the same rigour as HTTP bodies — a
 * WebSocket is not a trusted channel just because it is authenticated.
 */
const inboundSchema = z.discriminatedUnion('t', [
  z.object({ t: z.literal('ping') }),
  z.object({ t: z.literal('subscribe'), conversationIds: z.array(z.string().max(64)).max(500) }),
  z.object({ t: z.literal('typing.set'), conversationId: z.string().max(64), isTyping: z.boolean() }),
  z.object({ t: z.literal('presence.set'), isActive: z.boolean() }),
  z.object({
    t: z.literal('call.offer'),
    callId: z.string().max(64),
    toUserId: z.string().max(64),
    sdp: z.string().max(64_000),
  }),
  z.object({
    t: z.literal('call.answer'),
    callId: z.string().max(64),
    toUserId: z.string().max(64),
    sdp: z.string().max(64_000),
  }),
  z.object({
    t: z.literal('call.ice'),
    callId: z.string().max(64),
    toUserId: z.string().max(64),
    candidate: z.string().max(4_000),
    sdpMid: z.string().max(64).nullish(),
    sdpMLineIndex: z.number().int().min(0).max(64).default(0),
  }),
  z.object({
    t: z.literal('call.hangup'),
    callId: z.string().max(64),
    toUserId: z.string().max(64),
    reason: z.string().max(32).default('ended'),
  }),
]);

export async function realtimeGateway(app: FastifyInstance): Promise<void> {
  app.get('/v1/realtime', { websocket: true }, async (socket: WebSocket, request: FastifyRequest) => {
    const header = request.headers.authorization;
    if (!header?.startsWith('Bearer ')) {
      socket.close(4401, 'unauthorized');
      return;
    }

    let userId: string;
    let deviceId: string;
    try {
      const claims = await verifyAccessToken(header.slice('Bearer '.length).trim());
      const device = await queryOne<{ revoked_at: number | null }>(
        'SELECT revoked_at FROM devices WHERE id = $1 AND user_id = $2',
        [claims.did, claims.sub],
      );
      if (!device || device.revoked_at !== null) {
        socket.close(4401, 'session revoked');
        return;
      }
      userId = claims.sub;
      deviceId = claims.did;
    } catch {
      socket.close(4401, 'unauthorized');
      return;
    }

    const connection = hub.add(socket, userId, deviceId);
    await markOnline(userId, true);
    await broadcastPresence(userId, true);

    socket.on('message', (raw: Buffer | string) => {
      void handleFrame(String(raw), userId, deviceId, connection.subscriptions);
    });

    socket.on('close', () => {
      hub.remove(connection);
      // Only mark offline when this was the user's last connection; a phone
      // and a tablet should not fight over presence.
      if (!hub.isOnline(userId)) {
        void markOnline(userId, false).then(() => broadcastPresence(userId, false));
      }
    });

    socket.on('error', () => {
      hub.remove(connection);
    });
  });
}

async function handleFrame(
  raw: string,
  userId: string,
  deviceId: string,
  subscriptions: Set<string>,
): Promise<void> {
  let parsed: z.infer<typeof inboundSchema>;
  try {
    parsed = inboundSchema.parse(JSON.parse(raw));
  } catch {
    // A malformed frame is dropped rather than closing the socket: one bad
    // frame from a buggy build should not disconnect the whole session.
    return;
  }

  switch (parsed.t) {
    case 'ping':
      hub.toDevice(deviceId, { t: 'ping' });
      return;

    case 'subscribe': {
      subscriptions.clear();
      for (const id of parsed.conversationIds) subscriptions.add(id);
      return;
    }

    case 'typing.set': {
      const privacy = await privacyFor(userId);
      if (!privacy.typing_indicators) return;

      const members = await conversationMemberIds(parsed.conversationId, userId);
      if (members.length === 0) return;

      const sender = await queryOne<{ display_name: string }>(
        'SELECT display_name FROM users WHERE id = $1',
        [userId],
      );
      hub.toUsers(members, {
        t: 'typing',
        conversationId: parsed.conversationId,
        userId,
        userName: sender?.display_name ?? '',
        isTyping: parsed.isTyping,
      });
      return;
    }

    case 'presence.set':
      await markOnline(userId, parsed.isActive);
      await broadcastPresence(userId, parsed.isActive);
      return;

    // ---- WebRTC signalling ------------------------------------------------
    //
    // The server relays SDP and ICE without inspecting them. It is a
    // rendezvous point, not a media path: once the peers connect, audio and
    // video flow directly (or through TURN) and never touch this process.
    case 'call.offer':
    case 'call.answer':
    case 'call.ice':
    case 'call.hangup': {
      if (await isBlockedEitherWay(userId, parsed.toUserId)) return;

      const { toUserId, ...rest } = parsed;
      hub.toUser(toUserId, { ...rest, fromUserId: userId });
      return;
    }
  }
}

async function conversationMemberIds(conversationId: string, exceptUserId: string): Promise<string[]> {
  // The membership check and the recipient list are one query: a caller who is
  // not a member gets an empty list, so they cannot use typing frames to probe
  // conversations they are not in.
  const rows = await query<{ user_id: string }>(
    `SELECT m.user_id FROM conversation_members m
     WHERE m.conversation_id = $1
       AND m.left_at IS NULL
       AND EXISTS (
         SELECT 1 FROM conversation_members self
         WHERE self.conversation_id = $1 AND self.user_id = $2 AND self.left_at IS NULL
       )`,
    [conversationId, exceptUserId],
  );
  return rows.map((row) => row.user_id).filter((id) => id !== exceptUserId);
}

/** Tells the user's contacts that they came online or went away. */
async function broadcastPresence(userId: string, online: boolean): Promise<void> {
  const privacy = await privacyFor(userId);
  if (privacy.online_status === 'NOBODY') return;

  const audience = await query<{ user_id: string }>(
    privacy.online_status === 'EVERYONE'
      ? `SELECT DISTINCT other.user_id FROM conversation_members me
         JOIN conversation_members other ON other.conversation_id = me.conversation_id
         WHERE me.user_id = $1 AND other.user_id <> $1 AND other.left_at IS NULL`
      : 'SELECT user_id FROM contacts WHERE contact_id = $1',
    [userId],
  );

  hub.toUsers(
    audience.map((row) => row.user_id),
    { t: 'presence', userId, isOnline: online, lastSeenAt: Date.now(), hidden: false },
  );
}
