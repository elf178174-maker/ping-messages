import type { FastifyReply, FastifyRequest } from 'fastify';
import { query, queryOne } from '../db/pool.js';
import { forbidden, unauthorized } from './errors.js';
import { verifyAccessToken } from './tokens.js';

declare module 'fastify' {
  interface FastifyRequest {
    userId?: string;
    deviceId?: string;
  }
}

/**
 * Authenticates a request from its bearer token.
 *
 * Registered as a route-level `preHandler` rather than a global hook, so an
 * endpoint is authenticated only by explicitly opting in — a new public route
 * cannot accidentally inherit protection it does not have, and a new private
 * route that forgets this fails closed on the first authorisation check.
 */
export async function requireAuth(request: FastifyRequest, _reply: FastifyReply): Promise<void> {
  const header = request.headers.authorization;
  if (!header?.startsWith('Bearer ')) {
    throw unauthorized();
  }

  const claims = await verifyAccessToken(header.slice('Bearer '.length).trim());

  // The device must still exist and be un-revoked. This is the one database
  // read per authenticated request, and it is what makes "sign out this
  // device" take effect within the access-token TTL rather than at expiry.
  const device = await queryOne<{ user_id: string; revoked_at: number | null }>(
    'SELECT user_id, revoked_at FROM devices WHERE id = $1',
    [claims.did],
  );

  if (!device || device.revoked_at !== null || device.user_id !== claims.sub) {
    throw unauthorized('Session is no longer valid');
  }

  request.userId = claims.sub;
  request.deviceId = claims.did;

  // Fire-and-forget: last-active is a nicety, and awaiting it would add a
  // write to the critical path of every single request.
  void query('UPDATE devices SET last_active_at = $1 WHERE id = $2', [Date.now(), claims.did]);
}

/** The signed-in user id, or throws. Use inside a route that has `requireAuth`. */
export function currentUser(request: FastifyRequest): string {
  const userId = request.userId;
  if (!userId) throw unauthorized();
  return userId;
}

export function currentDevice(request: FastifyRequest): string {
  const deviceId = request.deviceId;
  if (!deviceId) throw unauthorized();
  return deviceId;
}

/** Throws unless [userId] is an active member of [conversationId]. */
export async function assertMember(conversationId: string, userId: string): Promise<void> {
  const row = await queryOne(
    `SELECT 1 FROM conversation_members
     WHERE conversation_id = $1 AND user_id = $2 AND left_at IS NULL`,
    [conversationId, userId],
  );
  if (!row) throw forbidden('You are not a member of this conversation');
}

/** Throws unless [userId] is an admin or owner of [conversationId]. */
export async function assertAdmin(conversationId: string, userId: string): Promise<void> {
  const row = await queryOne<{ role: string }>(
    `SELECT role FROM conversation_members
     WHERE conversation_id = $1 AND user_id = $2 AND left_at IS NULL`,
    [conversationId, userId],
  );
  if (!row || row.role === 'MEMBER') throw forbidden('Only group admins can do that');
}

/**
 * True when [a] has blocked [b] or vice versa.
 *
 * Checked in both directions before delivering anything between two people:
 * blocking is not just "hide their messages", it has to stop delivery in both
 * directions or the blocked party can still see read receipts and presence.
 */
export async function isBlockedEitherWay(a: string, b: string): Promise<boolean> {
  const row = await queryOne(
    `SELECT 1 FROM blocks
     WHERE (user_id = $1 AND blocked_id = $2) OR (user_id = $2 AND blocked_id = $1)
     LIMIT 1`,
    [a, b],
  );
  return row !== null;
}
