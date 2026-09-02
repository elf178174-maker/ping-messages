import { query, queryOne } from '../db/pool.js';

export type Audience = 'EVERYONE' | 'CONTACTS' | 'NOBODY';

export interface PrivacyRow {
  last_seen: Audience;
  online_status: Audience;
  profile_photo: Audience;
  about: Audience;
  status: Audience;
  groups: Audience;
  calls: Audience;
  read_receipts: boolean;
  typing_indicators: boolean;
}

const DEFAULT_PRIVACY: PrivacyRow = {
  last_seen: 'CONTACTS',
  online_status: 'EVERYONE',
  profile_photo: 'CONTACTS',
  about: 'CONTACTS',
  status: 'CONTACTS',
  groups: 'EVERYONE',
  calls: 'EVERYONE',
  read_receipts: true,
  typing_indicators: true,
};

export async function privacyFor(userId: string): Promise<PrivacyRow> {
  const row = await queryOne<PrivacyRow>('SELECT * FROM user_privacy WHERE user_id = $1', [userId]);
  return row ?? DEFAULT_PRIVACY;
}

/**
 * Whether [viewerId] is allowed to see a field of [ownerId]'s profile.
 *
 * Enforced here, on the server, rather than by the client choosing what to
 * render — a privacy setting the client could ignore is not a privacy setting.
 */
export async function canView(
  ownerId: string,
  viewerId: string,
  audience: Audience,
): Promise<boolean> {
  if (ownerId === viewerId) return true;
  if (audience === 'NOBODY') return false;
  if (audience === 'EVERYONE') return true;

  const contact = await queryOne(
    'SELECT 1 FROM contacts WHERE user_id = $1 AND contact_id = $2',
    [ownerId, viewerId],
  );
  return contact !== null;
}

/**
 * Redacts a user row according to the owner's privacy settings.
 *
 * The shape returned is always the same; hidden fields are null and presence
 * is marked hidden. Returning a different shape would leak the setting itself.
 */
export async function projectUser(
  row: {
    id: string;
    username: string;
    display_name: string;
    about: string;
    avatar_url: string | null;
    updated_at: number;
  },
  viewerId: string,
): Promise<Record<string, unknown>> {
  const privacy = await privacyFor(row.id);
  const [showPhoto, showAbout, showLastSeen, showOnline] = await Promise.all([
    canView(row.id, viewerId, privacy.profile_photo),
    canView(row.id, viewerId, privacy.about),
    canView(row.id, viewerId, privacy.last_seen),
    canView(row.id, viewerId, privacy.online_status),
  ]);

  const presence = await queryOne<{ is_online: boolean; last_seen_at: number | null }>(
    'SELECT is_online, last_seen_at FROM user_presence WHERE user_id = $1',
    [row.id],
  );

  const [isContact, publicKey] = await Promise.all([
    queryOne('SELECT 1 FROM contacts WHERE user_id = $1 AND contact_id = $2', [viewerId, row.id]),
    queryOne<{ public_key: string | null }>(
      `SELECT public_key FROM devices
       WHERE user_id = $1 AND revoked_at IS NULL AND public_key IS NOT NULL
       ORDER BY last_active_at DESC LIMIT 1`,
      [row.id],
    ),
  ]);

  const blocked = await queryOne(
    'SELECT 1 FROM blocks WHERE user_id = $1 AND blocked_id = $2',
    [viewerId, row.id],
  );

  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    about: showAbout ? row.about : '',
    avatarUrl: showPhoto ? row.avatar_url : null,
    // Phone numbers are never returned to another user, whatever the settings.
    phoneNumber: null,
    publicKey: publicKey?.public_key ?? null,
    isOnline: showOnline ? (presence?.is_online ?? false) : false,
    lastSeenAt: showLastSeen ? (presence?.last_seen_at ?? null) : null,
    presenceHidden: !showOnline && !showLastSeen,
    isContact: isContact !== null,
    isBlocked: blocked !== null,
    updatedAt: row.updated_at,
  };
}

export async function markOnline(userId: string, online: boolean): Promise<void> {
  await query(
    `INSERT INTO user_presence (user_id, is_online, last_seen_at)
     VALUES ($1, $2, $3)
     ON CONFLICT (user_id) DO UPDATE SET is_online = $2, last_seen_at = $3`,
    [userId, online, Date.now()],
  );
}
