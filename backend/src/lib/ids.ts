import { randomBytes, randomUUID, createHash } from 'node:crypto';

/** Server-generated entity id. Clients generate their own for messages. */
export const newId = (): string => randomUUID();

/**
 * A short, URL-safe, unambiguous code for group invite links.
 *
 * The alphabet omits 0/O and 1/l/I so a code read aloud or copied by hand does
 * not turn into a different valid code. 12 characters of this alphabet is
 * about 62 bits — far beyond guessable at any realistic request rate.
 */
const INVITE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';

export function newInviteCode(length = 12): string {
  const bytes = randomBytes(length);
  let out = '';
  for (let i = 0; i < length; i += 1) {
    out += INVITE_ALPHABET[bytes[i]! % INVITE_ALPHABET.length];
  }
  return out;
}

/** A numeric verification code. Always six digits, zero-padded. */
export function newVerificationCode(): string {
  // rejection-free: 1_000_000 divides evenly into the 2^24 space closely
  // enough that modulo bias here is negligible for a 10-minute, 5-attempt code.
  const value = randomBytes(4).readUInt32BE(0) % 1_000_000;
  return value.toString().padStart(6, '0');
}

/** An opaque refresh token. Stored hashed; the plaintext only ever goes to the client. */
export const newRefreshToken = (): string => randomBytes(48).toString('base64url');

/** SHA-256, used for storing refresh tokens and comparing contact hashes. */
export const sha256 = (value: string): string =>
  createHash('sha256').update(value).digest('hex');

/**
 * The canonical key for a one-to-one conversation between two users.
 *
 * Sorting first is what makes the pair symmetric: (a, b) and (b, a) produce the
 * same key, so the unique index prevents two conversations for one pair no
 * matter who starts it.
 */
export const directPairKey = (a: string, b: string): string => [a, b].sort().join(':');
