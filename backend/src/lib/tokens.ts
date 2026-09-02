import { SignJWT, jwtVerify } from 'jose';
import { config } from '../config.js';
import { unauthorized } from './errors.js';

const accessSecret = new TextEncoder().encode(config.JWT_ACCESS_SECRET);
const refreshSecret = new TextEncoder().encode(config.JWT_REFRESH_SECRET);

const ISSUER = 'ping';
const AUDIENCE = 'ping-app';

export interface AccessClaims {
  sub: string;
  did: string;
}

/**
 * Access tokens.
 *
 * Short-lived (15 minutes by default) and stateless, so the common case — a
 * request with a valid token — costs no database round trip. Revocation is
 * handled at refresh time instead: a revoked device cannot mint a new access
 * token, so the blast radius of a stolen access token is bounded by its TTL.
 *
 * The device id travels in the token so a request can be attributed to a
 * specific device without a lookup, which is what makes the linked-devices
 * screen's "last active" accurate.
 */
export async function signAccessToken(userId: string, deviceId: string): Promise<string> {
  return new SignJWT({ did: deviceId })
    .setProtectedHeader({ alg: 'HS256', typ: 'JWT' })
    .setSubject(userId)
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setIssuedAt()
    .setExpirationTime(`${config.ACCESS_TOKEN_TTL_SECONDS}s`)
    .sign(accessSecret);
}

export async function verifyAccessToken(token: string): Promise<AccessClaims> {
  try {
    const { payload } = await jwtVerify(token, accessSecret, {
      issuer: ISSUER,
      audience: AUDIENCE,
      algorithms: ['HS256'],
    });

    const sub = payload.sub;
    const did = payload.did;
    if (typeof sub !== 'string' || typeof did !== 'string') {
      throw unauthorized('Malformed token');
    }
    return { sub, did };
  } catch {
    // Deliberately uniform: expired, tampered and malformed all look the same
    // to a caller, so nothing can be probed by comparing error responses.
    throw unauthorized('Invalid or expired token');
  }
}

/**
 * A signed wrapper around the opaque refresh token.
 *
 * The signature lets the server reject a garbage token without a database
 * lookup; the opaque value inside is what is actually checked (hashed) against
 * the devices table, so a valid signature alone is not sufficient.
 */
export async function signRefreshToken(
  userId: string,
  deviceId: string,
  opaque: string,
): Promise<string> {
  return new SignJWT({ did: deviceId, tok: opaque })
    .setProtectedHeader({ alg: 'HS256', typ: 'JWT' })
    .setSubject(userId)
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setIssuedAt()
    .setExpirationTime(`${config.REFRESH_TOKEN_TTL_DAYS}d`)
    .sign(refreshSecret);
}

export async function verifyRefreshToken(
  token: string,
): Promise<{ userId: string; deviceId: string; opaque: string }> {
  try {
    const { payload } = await jwtVerify(token, refreshSecret, {
      issuer: ISSUER,
      audience: AUDIENCE,
      algorithms: ['HS256'],
    });

    const sub = payload.sub;
    const did = payload.did;
    const tok = payload.tok;
    if (typeof sub !== 'string' || typeof did !== 'string' || typeof tok !== 'string') {
      throw unauthorized('Malformed refresh token');
    }
    return { userId: sub, deviceId: did, opaque: tok };
  } catch {
    throw unauthorized('Invalid or expired refresh token');
  }
}

export const refreshExpiryMillis = (): number =>
  Date.now() + config.REFRESH_TOKEN_TTL_DAYS * 24 * 60 * 60 * 1000;
