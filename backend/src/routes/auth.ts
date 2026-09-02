import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { config } from '../config.js';
import { query, queryOne, transaction } from '../db/pool.js';
import { requireAuth, currentUser, currentDevice } from '../lib/auth.js';
import { sendEmail } from '../lib/email.js';
import { badRequest, conflict, forbidden, notFound, unauthorized } from '../lib/errors.js';
import { newId, newRefreshToken, newVerificationCode, sha256 } from '../lib/ids.js';
import { hashPassword, needsRehash, verifyPassword } from '../lib/passwords.js';
import {
  refreshExpiryMillis,
  signAccessToken,
  signRefreshToken,
  verifyRefreshToken,
} from '../lib/tokens.js';
import {
  assertPasswordAcceptable,
  codeSchema,
  displayNameSchema,
  emailSchema,
  parse,
  passwordSchema,
  pinSchema,
  usernameSchema,
} from '../lib/validation.js';

const CODE_TTL_MS = 10 * 60 * 1000;
const MAX_CODE_ATTEMPTS = 5;

interface UserRow {
  id: string;
  email: string;
  email_verified: boolean;
  password_hash: string;
  username: string;
  display_name: string;
  about: string;
  avatar_url: string | null;
  two_step_pin_hash: string | null;
  updated_at: number;
}

export async function authRoutes(app: FastifyInstance): Promise<void> {
  // ---- Registration -------------------------------------------------------

  app.post('/v1/auth/register', async (request) => {
    const body = parse(
      z.object({
        email: emailSchema,
        password: passwordSchema,
        username: usernameSchema,
        displayName: displayNameSchema,
        deviceName: z.string().trim().min(1).max(120),
        platform: z.string().trim().max(32).default('android'),
        publicKey: z.string().max(4096).optional(),
      }),
      request.body,
    );

    assertPasswordAcceptable(body.password);

    const emailNormalised = body.email.toLowerCase();
    const now = Date.now();

    const existingEmail = await queryOne(
      'SELECT 1 FROM users WHERE email_normalised = $1 AND is_deleted = FALSE',
      [emailNormalised],
    );
    if (existingEmail) {
      // A distinct message here is a deliberate trade: it tells an attacker the
      // address is registered, but the alternative — silently "succeeding" —
      // strands a real user who simply forgot they had an account.
      throw conflict('An account already exists for that email address', {
        email: 'An account already exists for that email address',
      });
    }

    const existingUsername = await queryOne(
      'SELECT 1 FROM users WHERE lower(username) = $1 AND is_deleted = FALSE',
      [body.username],
    );
    if (existingUsername) {
      throw conflict('That username is already taken', { username: 'That username is taken' });
    }

    const passwordHash = await hashPassword(body.password);
    const userId = newId();
    const deviceId = newId();
    const opaque = newRefreshToken();
    const code = newVerificationCode();

    await transaction(async (client) => {
      await client.query(
        `INSERT INTO users
           (id, email, email_normalised, email_verified, password_hash, username,
            display_name, about, created_at, updated_at)
         VALUES ($1, $2, $3, FALSE, $4, $5, $6, '', $7, $7)`,
        [userId, body.email, emailNormalised, passwordHash, body.username, body.displayName, now],
      );
      await client.query('INSERT INTO user_privacy (user_id) VALUES ($1)', [userId]);
      await client.query(
        'INSERT INTO user_presence (user_id, is_online, last_seen_at) VALUES ($1, FALSE, $2)',
        [userId, now],
      );
      await client.query(
        `INSERT INTO devices
           (id, user_id, name, platform, public_key, refresh_token_hash,
            refresh_expires_at, last_active_at, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8)`,
        [
          deviceId,
          userId,
          body.deviceName,
          body.platform,
          body.publicKey ?? null,
          sha256(opaque),
          refreshExpiryMillis(),
          now,
        ],
      );
      await client.query(
        `INSERT INTO verification_codes (id, user_id, purpose, code_hash, expires_at, created_at)
         VALUES ($1, $2, 'email_verify', $3, $4, $5)`,
        [newId(), userId, sha256(code), now + CODE_TTL_MS, now],
      );
    });

    await sendEmail({
      to: body.email,
      subject: 'Your Ping verification code',
      text: `Your Ping verification code is ${code}. It expires in 10 minutes.`,
    });

    return issueSession(userId, deviceId, opaque, false);
  });

  // ---- Login --------------------------------------------------------------

  app.post('/v1/auth/login', async (request) => {
    const body = parse(
      z.object({
        email: emailSchema,
        password: z.string().min(1),
        deviceName: z.string().trim().min(1).max(120),
        platform: z.string().trim().max(32).default('android'),
        publicKey: z.string().max(4096).optional(),
        twoStepPin: z.string().optional(),
      }),
      request.body,
    );

    const user = await queryOne<UserRow>(
      'SELECT * FROM users WHERE email_normalised = $1 AND is_deleted = FALSE',
      [body.email.toLowerCase()],
    );

    // Hashing even when no user exists keeps the response time constant, so
    // login cannot be used to enumerate which addresses are registered.
    const storedHash = user?.password_hash ?? DUMMY_HASH;
    const passwordOk = await verifyPassword(body.password, storedHash);

    if (!user || !passwordOk) {
      throw unauthorized('Email or password is incorrect');
    }

    if (user.two_step_pin_hash) {
      if (!body.twoStepPin) {
        throw forbidden('This account requires two-step verification');
      }
      const pinOk = await verifyPassword(body.twoStepPin, user.two_step_pin_hash);
      if (!pinOk) throw forbidden('That two-step verification PIN is incorrect');
    }

    // Opportunistic upgrade when the hashing parameters have been raised.
    if (needsRehash(user.password_hash)) {
      const upgraded = await hashPassword(body.password);
      void query('UPDATE users SET password_hash = $1 WHERE id = $2', [upgraded, user.id]);
    }

    const now = Date.now();
    const deviceId = newId();
    const opaque = newRefreshToken();

    await query(
      `INSERT INTO devices
         (id, user_id, name, platform, public_key, refresh_token_hash,
          refresh_expires_at, last_active_at, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8)`,
      [
        deviceId,
        user.id,
        body.deviceName,
        body.platform,
        body.publicKey ?? null,
        sha256(opaque),
        refreshExpiryMillis(),
        now,
      ],
    );

    return issueSession(user.id, deviceId, opaque, user.email_verified);
  });

  // ---- Refresh ------------------------------------------------------------

  app.post('/v1/auth/refresh', async (request) => {
    const body = parse(z.object({ refreshToken: z.string().min(1) }), request.body);
    const claims = await verifyRefreshToken(body.refreshToken);

    const device = await queryOne<{ refresh_token_hash: string | null; refresh_expires_at: number | null; revoked_at: number | null }>(
      'SELECT refresh_token_hash, refresh_expires_at, revoked_at FROM devices WHERE id = $1 AND user_id = $2',
      [claims.deviceId, claims.userId],
    );

    if (
      !device ||
      device.revoked_at !== null ||
      device.refresh_token_hash !== sha256(claims.opaque) ||
      (device.refresh_expires_at ?? 0) < Date.now()
    ) {
      throw unauthorized('Please sign in again');
    }

    // Rotation: every refresh mints a new opaque token and invalidates the old
    // one. If a stolen token is used, the legitimate device's next refresh
    // fails and the user is signed out — which is a detectable, recoverable
    // outcome rather than a silently shared session.
    const opaque = newRefreshToken();
    await query(
      'UPDATE devices SET refresh_token_hash = $1, refresh_expires_at = $2, last_active_at = $3 WHERE id = $4',
      [sha256(opaque), refreshExpiryMillis(), Date.now(), claims.deviceId],
    );

    return {
      accessToken: await signAccessToken(claims.userId, claims.deviceId),
      refreshToken: await signRefreshToken(claims.userId, claims.deviceId, opaque),
      expiresIn: config.ACCESS_TOKEN_TTL_SECONDS,
    };
  });

  // ---- Email verification -------------------------------------------------

  app.post('/v1/auth/verify-email', async (request) => {
    const body = parse(z.object({ email: emailSchema, code: codeSchema }), request.body);

    const user = await queryOne<UserRow>(
      'SELECT * FROM users WHERE email_normalised = $1 AND is_deleted = FALSE',
      [body.email.toLowerCase()],
    );
    if (!user) throw badRequest('That code is not valid');

    const record = await queryOne<{ id: string; code_hash: string; expires_at: number; attempts: number }>(
      `SELECT id, code_hash, expires_at, attempts FROM verification_codes
       WHERE user_id = $1 AND purpose = 'email_verify' AND consumed = FALSE
       ORDER BY created_at DESC LIMIT 1`,
      [user.id],
    );

    if (!record || record.expires_at < Date.now()) throw badRequest('That code has expired');
    if (record.attempts >= MAX_CODE_ATTEMPTS) {
      throw badRequest('Too many attempts. Request a new code.');
    }

    if (record.code_hash !== sha256(body.code)) {
      await query('UPDATE verification_codes SET attempts = attempts + 1 WHERE id = $1', [record.id]);
      throw badRequest('That code is not valid');
    }

    const deviceId = newId();
    const opaque = newRefreshToken();
    const now = Date.now();

    await transaction(async (client) => {
      await client.query('UPDATE verification_codes SET consumed = TRUE WHERE id = $1', [record.id]);
      await client.query('UPDATE users SET email_verified = TRUE, updated_at = $1 WHERE id = $2', [
        now,
        user.id,
      ]);
      await client.query(
        `INSERT INTO devices
           (id, user_id, name, platform, refresh_token_hash, refresh_expires_at, last_active_at, created_at)
         VALUES ($1, $2, 'Verified device', 'android', $3, $4, $5, $5)`,
        [deviceId, user.id, sha256(opaque), refreshExpiryMillis(), now],
      );
    });

    return issueSession(user.id, deviceId, opaque, true);
  });

  app.post('/v1/auth/resend-code', async (request) => {
    const body = parse(z.object({ email: emailSchema }), request.body);
    const user = await queryOne<{ id: string; email: string }>(
      'SELECT id, email FROM users WHERE email_normalised = $1 AND is_deleted = FALSE',
      [body.email.toLowerCase()],
    );

    // Always the same answer, so this cannot confirm whether an address exists.
    if (user) {
      const code = newVerificationCode();
      const now = Date.now();
      await query(
        `INSERT INTO verification_codes (id, user_id, purpose, code_hash, expires_at, created_at)
         VALUES ($1, $2, 'email_verify', $3, $4, $5)`,
        [newId(), user.id, sha256(code), now + CODE_TTL_MS, now],
      );
      await sendEmail({
        to: user.email,
        subject: 'Your Ping verification code',
        text: `Your Ping verification code is ${code}. It expires in 10 minutes.`,
      });
    }

    return { ok: true };
  });

  // ---- Password reset -----------------------------------------------------

  app.post('/v1/auth/forgot-password', async (request) => {
    const body = parse(z.object({ email: emailSchema }), request.body);
    const user = await queryOne<{ id: string; email: string }>(
      'SELECT id, email FROM users WHERE email_normalised = $1 AND is_deleted = FALSE',
      [body.email.toLowerCase()],
    );

    if (user) {
      const token = newRefreshToken();
      const now = Date.now();
      await query(
        `INSERT INTO verification_codes (id, user_id, purpose, code_hash, expires_at, created_at)
         VALUES ($1, $2, 'password_reset', $3, $4, $5)`,
        [newId(), user.id, sha256(token), now + 60 * 60 * 1000, now],
      );
      await sendEmail({
        to: user.email,
        subject: 'Reset your Ping password',
        text: `Use this token to reset your password (valid for one hour):\n\n${token}`,
      });
    }

    return { ok: true, message: 'If that email is registered, a reset link is on its way.' };
  });

  app.post('/v1/auth/reset-password', async (request) => {
    const body = parse(
      z.object({ token: z.string().min(10), newPassword: passwordSchema }),
      request.body,
    );
    assertPasswordAcceptable(body.newPassword);

    const record = await queryOne<{ id: string; user_id: string; expires_at: number }>(
      `SELECT id, user_id, expires_at FROM verification_codes
       WHERE purpose = 'password_reset' AND consumed = FALSE AND code_hash = $1`,
      [sha256(body.token)],
    );
    if (!record || record.expires_at < Date.now()) {
      throw badRequest('That reset link has expired. Request a new one.');
    }

    const passwordHash = await hashPassword(body.newPassword);
    await transaction(async (client) => {
      await client.query('UPDATE verification_codes SET consumed = TRUE WHERE id = $1', [record.id]);
      await client.query('UPDATE users SET password_hash = $1, updated_at = $2 WHERE id = $3', [
        passwordHash,
        Date.now(),
        record.user_id,
      ]);
      // Changing a password signs every device out. Anyone who reset the
      // password because they suspected compromise expects exactly that.
      await client.query(
        'UPDATE devices SET revoked_at = $1, refresh_token_hash = NULL WHERE user_id = $2 AND revoked_at IS NULL',
        [Date.now(), record.user_id],
      );
    });

    return { ok: true };
  });

  // ---- Username availability ---------------------------------------------

  app.get('/v1/auth/username-available', async (request) => {
    const params = parse(z.object({ username: usernameSchema }), request.query);
    const taken = await queryOne(
      'SELECT 1 FROM users WHERE lower(username) = $1 AND is_deleted = FALSE',
      [params.username],
    );
    return { username: params.username, available: taken === null };
  });

  // ---- Authenticated session management ----------------------------------

  app.post('/v1/auth/logout', { preHandler: requireAuth }, async (request) => {
    await query(
      'UPDATE devices SET revoked_at = $1, refresh_token_hash = NULL WHERE id = $2',
      [Date.now(), currentDevice(request)],
    );
    return { ok: true };
  });

  app.post('/v1/auth/change-password', { preHandler: requireAuth }, async (request) => {
    const body = parse(
      z.object({ currentPassword: z.string().min(1), newPassword: passwordSchema }),
      request.body,
    );
    assertPasswordAcceptable(body.newPassword);

    const userId = currentUser(request);
    const user = await queryOne<{ password_hash: string }>(
      'SELECT password_hash FROM users WHERE id = $1',
      [userId],
    );
    if (!user || !(await verifyPassword(body.currentPassword, user.password_hash))) {
      throw forbidden('Your current password is incorrect');
    }

    const hash = await hashPassword(body.newPassword);
    await transaction(async (client) => {
      await client.query('UPDATE users SET password_hash = $1, updated_at = $2 WHERE id = $3', [
        hash,
        Date.now(),
        userId,
      ]);
      // Every device except the one making the change.
      await client.query(
        `UPDATE devices SET revoked_at = $1, refresh_token_hash = NULL
         WHERE user_id = $2 AND id <> $3 AND revoked_at IS NULL`,
        [Date.now(), userId, currentDevice(request)],
      );
    });

    return { ok: true };
  });

  app.post('/v1/auth/two-step', { preHandler: requireAuth }, async (request) => {
    const body = parse(
      z.object({ pin: pinSchema.nullable().optional(), currentPassword: z.string().min(1) }),
      request.body,
    );

    const userId = currentUser(request);
    const user = await queryOne<{ password_hash: string }>(
      'SELECT password_hash FROM users WHERE id = $1',
      [userId],
    );
    if (!user || !(await verifyPassword(body.currentPassword, user.password_hash))) {
      throw forbidden('Your password is incorrect');
    }

    // The PIN is hashed with the same scrypt parameters as a password. It is
    // only six digits, so the work factor is what makes offline guessing
    // expensive rather than the entropy.
    const pinHash = body.pin ? await hashPassword(body.pin) : null;
    await query('UPDATE users SET two_step_pin_hash = $1, updated_at = $2 WHERE id = $3', [
      pinHash,
      Date.now(),
      userId,
    ]);

    return { ok: true };
  });
}

/**
 * A precomputed hash used when no user matches, so an unknown address costs
 * the same time as a known one with a wrong password.
 */
const DUMMY_HASH =
  'scrypt$131072$8$1$AAAAAAAAAAAAAAAAAAAAAA==$' +
  'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';

async function issueSession(
  userId: string,
  deviceId: string,
  opaque: string,
  emailVerified: boolean,
) {
  const user = await queryOne<UserRow>('SELECT * FROM users WHERE id = $1', [userId]);
  if (!user) throw notFound('User');

  return {
    accessToken: await signAccessToken(userId, deviceId),
    refreshToken: await signRefreshToken(userId, deviceId, opaque),
    expiresIn: config.ACCESS_TOKEN_TTL_SECONDS,
    deviceId,
    emailVerified,
    requiresTwoStep: false,
    user: {
      id: user.id,
      username: user.username,
      displayName: user.display_name,
      about: user.about,
      avatarUrl: user.avatar_url,
      phoneNumber: null,
      publicKey: null,
      isOnline: true,
      lastSeenAt: Date.now(),
      presenceHidden: false,
      isContact: false,
      isBlocked: false,
      updatedAt: user.updated_at,
    },
  };
}
