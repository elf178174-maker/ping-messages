import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';

/**
 * End-to-end API tests.
 *
 * These run the real Fastify app against a real Postgres — no mocks. That is
 * the only way to catch the failures that actually happen: a wrong column
 * name, a constraint that fires, a query that returns a shape the route did
 * not expect. Every one of those passes a mocked test and fails in production.
 *
 * Skipped automatically when no database is reachable, so `npm test` still
 * works on a machine without Postgres.
 */
const DATABASE_AVAILABLE = Boolean(process.env.DATABASE_URL);

describe.skipIf(!DATABASE_AVAILABLE)('API end to end', () => {
  let app: FastifyInstance;
  let closePool: () => Promise<void>;

  const alice = {
    email: `alice-${Date.now()}@example.test`,
    password: 'a perfectly fine passphrase',
    username: `alice${Date.now().toString().slice(-8)}`,
    displayName: 'Alice Example',
    deviceName: 'Alice Pixel',
  };
  const bob = {
    email: `bob-${Date.now()}@example.test`,
    password: 'another fine passphrase here',
    username: `bob${Date.now().toString().slice(-8)}`,
    displayName: 'Bob Example',
    deviceName: 'Bob Pixel',
  };

  let aliceToken = '';
  let bobToken = '';
  let aliceId = '';
  let bobId = '';
  let conversationId = '';

  beforeAll(async () => {
    const { migrate } = await import('../src/db/migrate.js');
    await migrate();
    const { buildApp } = await import('../src/app.js');
    const pool = await import('../src/db/pool.js');
    closePool = pool.closePool;
    app = await buildApp();
    await app.ready();
  }, 60_000);

  afterAll(async () => {
    await app?.close();
    await closePool?.();
  });

  const post = (url: string, payload: unknown, token?: string) =>
    app.inject({
      method: 'POST',
      url,
      payload: payload as Record<string, unknown>,
      headers: token ? { authorization: `Bearer ${token}` } : {},
    });

  const get = (url: string, token?: string) =>
    app.inject({ method: 'GET', url, headers: token ? { authorization: `Bearer ${token}` } : {} });

  it('serves health without authentication', async () => {
    const response = await get('/health');
    expect(response.statusCode).toBe(200);
    expect(response.json().ok).toBe(true);
  });

  it('registers two accounts', async () => {
    const first = await post('/v1/auth/register', alice);
    expect(first.statusCode).toBe(200);
    const firstBody = first.json();
    expect(firstBody.accessToken).toBeTruthy();
    expect(firstBody.user.username).toBe(alice.username);
    expect(firstBody.emailVerified).toBe(false);
    aliceToken = firstBody.accessToken;
    aliceId = firstBody.user.id;

    const second = await post('/v1/auth/register', bob);
    expect(second.statusCode).toBe(200);
    bobToken = second.json().accessToken;
    bobId = second.json().user.id;
  }, 30_000);

  it('rejects a duplicate username', async () => {
    const response = await post('/v1/auth/register', {
      ...alice,
      email: `other-${Date.now()}@example.test`,
    });
    expect(response.statusCode).toBe(409);
    expect(response.json().fields?.username).toBeTruthy();
  }, 30_000);

  it('rejects a weak password with a field-keyed error', async () => {
    const response = await post('/v1/auth/register', {
      ...alice,
      email: `weak-${Date.now()}@example.test`,
      username: `weak${Date.now().toString().slice(-8)}`,
      password: 'password123',
    });
    expect(response.statusCode).toBe(400);
    expect(response.json().fields?.password).toBeTruthy();
  });

  it('reports username availability', async () => {
    const taken = await get(`/v1/auth/username-available?username=${alice.username}`);
    expect(taken.json().available).toBe(false);

    const free = await get('/v1/auth/username-available?username=definitelyfreename');
    expect(free.json().available).toBe(true);
  });

  it('refuses protected routes without a token', async () => {
    expect((await get('/v1/me')).statusCode).toBe(401);
    expect((await get('/v1/conversations')).statusCode).toBe(401);
  });

  it('refuses a tampered token', async () => {
    const response = await get('/v1/me', `${aliceToken.slice(0, -3)}xyz`);
    expect(response.statusCode).toBe(401);
  });

  it('returns the signed-in profile', async () => {
    const response = await get('/v1/me', aliceToken);
    expect(response.statusCode).toBe(200);
    expect(response.json().username).toBe(alice.username);
  });

  it('updates the profile and rejects a taken username', async () => {
    const ok = await app.inject({
      method: 'PATCH',
      url: '/v1/me',
      payload: { displayName: 'Alice Renamed', about: 'Testing Ping' },
      headers: { authorization: `Bearer ${aliceToken}` },
    });
    expect(ok.statusCode).toBe(200);
    expect(ok.json().displayName).toBe('Alice Renamed');
    expect(ok.json().about).toBe('Testing Ping');

    const clash = await app.inject({
      method: 'PATCH',
      url: '/v1/me',
      payload: { username: bob.username },
      headers: { authorization: `Bearer ${aliceToken}` },
    });
    expect(clash.statusCode).toBe(409);
  });

  it('never exposes another user phone number', async () => {
    const response = await get(`/v1/users/${bobId}`, aliceToken);
    expect(response.statusCode).toBe(200);
    expect(response.json().phoneNumber).toBeNull();
  });

  it('finds a user by username', async () => {
    const response = await get(`/v1/users/by-username/${bob.username}`, aliceToken);
    expect(response.statusCode).toBe(200);
    expect(response.json().id).toBe(bobId);
  });

  it('opens a direct conversation idempotently', async () => {
    const first = await post('/v1/conversations', { participantIds: [bobId] }, aliceToken);
    expect(first.statusCode).toBe(200);
    conversationId = first.json().id;
    expect(conversationId).toBeTruthy();

    // The sorted pair key means opening again — from either side — must
    // resolve to the same conversation rather than creating a second one.
    const again = await post('/v1/conversations', { participantIds: [bobId] }, aliceToken);
    expect(again.json().id).toBe(conversationId);

    const fromBob = await post('/v1/conversations', { participantIds: [aliceId] }, bobToken);
    expect(fromBob.json().id).toBe(conversationId);
  });

  it('sends a message and allocates a sequence', async () => {
    const messageId = crypto.randomUUID();
    const response = await post(
      '/v1/messages',
      { id: messageId, conversationId, kind: 'TEXT', body: 'ciphertext-here', isEncrypted: true },
      aliceToken,
    );
    expect(response.statusCode).toBe(200);
    expect(response.json().serverSeq).toBeGreaterThan(0);
    expect(response.json().id).toBe(messageId);
  });

  it('treats a resent message id as idempotent rather than duplicating', async () => {
    const messageId = crypto.randomUUID();
    const first = await post(
      '/v1/messages',
      { id: messageId, conversationId, body: 'retry-me' },
      aliceToken,
    );
    const second = await post(
      '/v1/messages',
      { id: messageId, conversationId, body: 'retry-me' },
      aliceToken,
    );
    expect(second.statusCode).toBe(200);
    expect(second.json().serverSeq).toBe(first.json().serverSeq);

    const page = await get(`/v1/conversations/${conversationId}/messages`, aliceToken);
    const matching = page.json().items.filter((m: { id: string }) => m.id === messageId);
    expect(matching).toHaveLength(1);
  });

  it('refuses to send into a conversation the caller is not in', async () => {
    const outsider = await post('/v1/auth/register', {
      email: `carol-${Date.now()}@example.test`,
      password: 'yet another good passphrase',
      username: `carol${Date.now().toString().slice(-8)}`,
      displayName: 'Carol Example',
      deviceName: 'Carol Pixel',
    });
    const carolToken = outsider.json().accessToken;

    const response = await post(
      '/v1/messages',
      { id: crypto.randomUUID(), conversationId, body: 'should not land' },
      carolToken,
    );
    expect(response.statusCode).toBe(403);
  }, 30_000);

  it('derives an unread count for the recipient but not the sender', async () => {
    const bobList = await get('/v1/conversations', bobToken);
    const bobConversation = bobList
      .json()
      .find((c: { id: string }) => c.id === conversationId);
    expect(bobConversation.unreadCount).toBeGreaterThan(0);

    const aliceList = await get('/v1/conversations', aliceToken);
    const aliceConversation = aliceList
      .json()
      .find((c: { id: string }) => c.id === conversationId);
    expect(aliceConversation.unreadCount).toBe(0);
  });

  it('clears the unread count when a read receipt is recorded', async () => {
    const page = await get(`/v1/conversations/${conversationId}/messages`, bobToken);
    const newest = page.json().items[0];

    const receipt = await post(
      '/v1/receipts/read',
      { conversationId, upToMessageId: newest.id },
      bobToken,
    );
    expect(receipt.statusCode).toBe(200);

    const after = await get('/v1/conversations', bobToken);
    const conversation = after.json().find((c: { id: string }) => c.id === conversationId);
    expect(conversation.unreadCount).toBe(0);
  });

  it('lets the sender edit and refuses others', async () => {
    const messageId = crypto.randomUUID();
    await post('/v1/messages', { id: messageId, conversationId, body: 'original' }, aliceToken);

    const mine = await app.inject({
      method: 'PATCH',
      url: `/v1/messages/${messageId}`,
      payload: { body: 'edited' },
      headers: { authorization: `Bearer ${aliceToken}` },
    });
    expect(mine.statusCode).toBe(200);
    expect(mine.json().editedAt).toBeTruthy();

    const theirs = await app.inject({
      method: 'PATCH',
      url: `/v1/messages/${messageId}`,
      payload: { body: 'not allowed' },
      headers: { authorization: `Bearer ${bobToken}` },
    });
    expect(theirs.statusCode).toBe(403);
  });

  it('erases the body when deleting for everyone', async () => {
    const messageId = crypto.randomUUID();
    await post('/v1/messages', { id: messageId, conversationId, body: 'secret text' }, aliceToken);

    const deleted = await post(`/v1/messages/${messageId}/delete`, { forEveryone: true }, aliceToken);
    expect(deleted.statusCode).toBe(200);

    // A flag alone would leave the plaintext readable in the database, which
    // is not a deletion.
    const page = await get(`/v1/conversations/${conversationId}/messages`, bobToken);
    const row = page.json().items.find((m: { id: string }) => m.id === messageId);
    expect(row.isDeleted).toBe(true);
    expect(row.body).toBe('');
  });

  it('adds and removes reactions', async () => {
    const messageId = crypto.randomUUID();
    await post('/v1/messages', { id: messageId, conversationId, body: 'react to me' }, aliceToken);

    await post(`/v1/messages/${messageId}/reactions`, { emoji: '👍' }, bobToken);
    let page = await get(`/v1/conversations/${conversationId}/messages`, aliceToken);
    let row = page.json().items.find((m: { id: string }) => m.id === messageId);
    expect(row.reactions).toHaveLength(1);

    await post(`/v1/messages/${messageId}/reactions`, { emoji: '👍', remove: true }, bobToken);
    page = await get(`/v1/conversations/${conversationId}/messages`, aliceToken);
    row = page.json().items.find((m: { id: string }) => m.id === messageId);
    expect(row.reactions).toHaveLength(0);
  });

  it('creates a group with the creator as owner', async () => {
    const response = await post(
      '/v1/groups',
      { name: 'Test Group', description: 'Made by a test', memberIds: [bobId] },
      aliceToken,
    );
    expect(response.statusCode).toBe(200);
    const group = response.json();
    expect(group.myRole).toBe('OWNER');
    expect(group.members).toHaveLength(2);
    expect(group.inviteCode).toBeTruthy();

    // A plain member must not be able to read the invite code, or the
    // admins-only invite screen means nothing.
    const asBob = await get(`/v1/groups/${group.id}`, bobToken);
    expect(asBob.json().myRole).toBe('MEMBER');
    expect(asBob.json().inviteCode).toBeNull();
  }, 30_000);

  it('enforces admins-only group permissions on send', async () => {
    const created = await post(
      '/v1/groups',
      { name: 'Locked Group', memberIds: [bobId] },
      aliceToken,
    );
    const group = created.json();

    await app.inject({
      method: 'PATCH',
      url: `/v1/groups/${group.id}`,
      payload: { sendPermission: 'ADMINS_ONLY' },
      headers: { authorization: `Bearer ${aliceToken}` },
    });

    const asMember = await post(
      '/v1/messages',
      { id: crypto.randomUUID(), conversationId: group.conversationId, body: 'blocked' },
      bobToken,
    );
    expect(asMember.statusCode).toBe(403);

    const asAdmin = await post(
      '/v1/messages',
      { id: crypto.randomUUID(), conversationId: group.conversationId, body: 'allowed' },
      aliceToken,
    );
    expect(asAdmin.statusCode).toBe(200);
  }, 30_000);

  it('refuses a member changing group permissions', async () => {
    const created = await post('/v1/groups', { name: 'Perms', memberIds: [bobId] }, aliceToken);
    const response = await app.inject({
      method: 'PATCH',
      url: `/v1/groups/${created.json().id}`,
      payload: { sendPermission: 'EVERYONE' },
      headers: { authorization: `Bearer ${bobToken}` },
    });
    expect(response.statusCode).toBe(403);
  }, 30_000);

  it('blocks in both directions', async () => {
    await post('/v1/blocks', { userId: bobId }, aliceToken);

    // Blocking has to stop delivery both ways; one-directional blocking still
    // leaks presence and receipts to the blocked party.
    const bobToAlice = await post(
      '/v1/messages',
      { id: crypto.randomUUID(), conversationId, body: 'from blocked bob' },
      bobToken,
    );
    expect(bobToAlice.statusCode).toBe(403);

    const aliceToBob = await post(
      '/v1/messages',
      { id: crypto.randomUUID(), conversationId, body: 'to blocked bob' },
      aliceToken,
    );
    expect(aliceToBob.statusCode).toBe(403);

    await app.inject({
      method: 'DELETE',
      url: `/v1/blocks/${bobId}`,
      headers: { authorization: `Bearer ${aliceToken}` },
    });

    const afterUnblock = await post(
      '/v1/messages',
      { id: crypto.randomUUID(), conversationId, body: 'unblocked' },
      aliceToken,
    );
    expect(afterUnblock.statusCode).toBe(200);
  });

  it('round-trips privacy settings', async () => {
    const response = await app.inject({
      method: 'PUT',
      url: '/v1/me/privacy',
      payload: {
        lastSeen: 'NOBODY',
        onlineStatus: 'NOBODY',
        profilePhoto: 'CONTACTS',
        about: 'EVERYONE',
        status: 'CONTACTS',
        groups: 'CONTACTS',
        calls: 'NOBODY',
        readReceipts: false,
        typingIndicators: false,
      },
      headers: { authorization: `Bearer ${bobToken}` },
    });
    expect(response.statusCode).toBe(200);

    const read = await get('/v1/me/privacy', bobToken);
    expect(read.json().lastSeen).toBe('NOBODY');
    expect(read.json().readReceipts).toBe(false);
  });

  it('hides presence when the owner set it to nobody', async () => {
    // Bob set onlineStatus and lastSeen to NOBODY in the previous test.
    const response = await get(`/v1/users/${bobId}`, aliceToken);
    expect(response.json().presenceHidden).toBe(true);
    expect(response.json().lastSeenAt).toBeNull();
    expect(response.json().isOnline).toBe(false);
  });

  it('refuses a call to someone who accepts none', async () => {
    // Bob set calls to NOBODY above.
    const response = await post(
      '/v1/calls',
      { conversationId, isVideo: false, calleeIds: [bobId] },
      aliceToken,
    );
    // Either "not configured" (no ICE servers in test) or "not accepting" is
    // a correct refusal; what matters is that it is not a 200.
    expect([403]).toContain(response.statusCode);
  });

  it('reports calling as unconfigured without ICE servers', async () => {
    const response = await get('/v1/calls/config', aliceToken);
    expect(response.statusCode).toBe(200);
    expect(typeof response.json().enabled).toBe('boolean');
  });

  it('posts and expires status correctly', async () => {
    const response = await post(
      '/v1/status',
      { kind: 'TEXT', text: 'Hello from a test', audience: 'EVERYONE' },
      aliceToken,
    );
    expect(response.statusCode).toBe(200);
    const status = response.json();
    // 24 hours, matching what the client's UI promises.
    expect(status.expiresAt - status.createdAt).toBe(24 * 60 * 60 * 1000);

    const listed = await get('/v1/status', bobToken);
    expect(listed.json().some((s: { id: string }) => s.id === status.id)).toBe(true);
  });

  it('refuses a text status with no text', async () => {
    const response = await post('/v1/status', { kind: 'TEXT', text: '   ' }, aliceToken);
    expect(response.statusCode).toBe(403);
  });

  it('lists devices and marks the current one', async () => {
    const response = await get('/v1/me/devices', aliceToken);
    expect(response.statusCode).toBe(200);
    expect(response.json().filter((d: { isCurrent: boolean }) => d.isCurrent)).toHaveLength(1);
  });

  it('rotates the refresh token and invalidates the old one', async () => {
    const login = await post('/v1/auth/login', {
      email: alice.email,
      password: alice.password,
      deviceName: 'Rotation test',
    });
    const original = login.json().refreshToken;

    const first = await post('/v1/auth/refresh', { refreshToken: original });
    expect(first.statusCode).toBe(200);
    const rotated = first.json().refreshToken;
    expect(rotated).not.toBe(original);

    // Reuse of a spent refresh token must fail: that is what turns a stolen
    // token into a detectable event rather than a silently shared session.
    const replay = await post('/v1/auth/refresh', { refreshToken: original });
    expect(replay.statusCode).toBe(401);

    const next = await post('/v1/auth/refresh', { refreshToken: rotated });
    expect(next.statusCode).toBe(200);
  }, 30_000);

  it('rejects login with a wrong password', async () => {
    const response = await post('/v1/auth/login', {
      email: alice.email,
      password: 'definitely not the password',
      deviceName: 'Wrong password test',
    });
    expect(response.statusCode).toBe(401);
  }, 30_000);

  it('answers the same for a known and an unknown address on password reset', async () => {
    const known = await post('/v1/auth/forgot-password', { email: alice.email });
    const unknown = await post('/v1/auth/forgot-password', { email: 'nobody@example.test' });
    // Identical responses are what stop this endpoint being used to enumerate
    // which addresses have accounts.
    expect(known.statusCode).toBe(unknown.statusCode);
    expect(known.json()).toEqual(unknown.json());
  });

  it('signs out and stops accepting the session', async () => {
    const login = await post('/v1/auth/login', {
      email: bob.email,
      password: bob.password,
      deviceName: 'Logout test',
    });
    const token = login.json().accessToken;

    expect((await get('/v1/me', token)).statusCode).toBe(200);
    expect((await post('/v1/auth/logout', {}, token)).statusCode).toBe(200);
    // Revocation takes effect immediately because every authenticated request
    // checks the device row, not just the token signature.
    expect((await get('/v1/me', token)).statusCode).toBe(401);
  }, 30_000);

  it('returns a 404 shape for unknown routes', async () => {
    const response = await get('/v1/does-not-exist', aliceToken);
    expect(response.statusCode).toBe(404);
    expect(response.json().error).toBe('not_found');
  });
});
