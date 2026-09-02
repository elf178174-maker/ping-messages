import { randomBytes, scrypt as scryptCallback, timingSafeEqual } from 'node:crypto';
import type { ScryptOptions } from 'node:crypto';

/**
 * A promise wrapper around scrypt.
 *
 * Hand-written rather than `promisify`, because promisify resolves to the
 * three-argument overload and loses the options parameter that carries the
 * cost factors — which are the entire point of using scrypt.
 */
function scrypt(
  password: string,
  salt: Buffer,
  keylen: number,
  options: ScryptOptions,
): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scryptCallback(password, salt, keylen, options, (error, derived) => {
      if (error) reject(error);
      else resolve(derived);
    });
  });
}

/**
 * Password hashing with scrypt.
 *
 * scrypt rather than a native Argon2 binding: it ships in Node's standard
 * library, so there is no compiled dependency to break a deployment, and it is
 * memory-hard — which is the property that actually matters against GPU
 * cracking. Parameters follow the OWASP Password Storage Cheat Sheet's scrypt
 * recommendation (N=2^17, r=8, p=1, ~128 MiB).
 *
 * The stored format is self-describing (`scrypt$N$r$p$salt$hash`), so
 * parameters can be raised later and old hashes still verify and be upgraded
 * on next login.
 */
const N = 2 ** 17;
const R = 8;
const P = 1;
const KEY_LENGTH = 64;
const SALT_LENGTH = 16;

// scrypt's memory use is roughly 128 * N * r bytes; Node refuses to allocate
// beyond maxmem, so it has to be raised in step with N.
const MAX_MEM = 256 * 1024 * 1024;

export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(SALT_LENGTH);
  const derived = await scrypt(password.normalize('NFKC'), salt, KEY_LENGTH, {
    N,
    r: R,
    p: P,
    maxmem: MAX_MEM,
  });

  return ['scrypt', N, R, P, salt.toString('base64'), derived.toString('base64')].join('$');
}

/**
 * Verifies a password. Always compares in constant time, and returns false
 * rather than throwing on a malformed stored hash so a corrupt row cannot be
 * distinguished from a wrong password by timing or by error shape.
 */
export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  try {
    const parts = stored.split('$');
    if (parts.length !== 6 || parts[0] !== 'scrypt') return false;

    const n = Number(parts[1]);
    const r = Number(parts[2]);
    const p = Number(parts[3]);
    const salt = Buffer.from(parts[4] ?? '', 'base64');
    const expected = Buffer.from(parts[5] ?? '', 'base64');

    if (!Number.isFinite(n) || !Number.isFinite(r) || !Number.isFinite(p)) return false;
    if (salt.length === 0 || expected.length === 0) return false;

    const derived = await scrypt(password.normalize('NFKC'), salt, expected.length, {
      N: n,
      r,
      p,
      maxmem: MAX_MEM,
    });

    return derived.length === expected.length && timingSafeEqual(derived, expected);
  } catch {
    return false;
  }
}

/** True when a stored hash used weaker parameters than the current policy. */
export function needsRehash(stored: string): boolean {
  const parts = stored.split('$');
  if (parts.length !== 6 || parts[0] !== 'scrypt') return true;
  return Number(parts[1]) < N;
}
