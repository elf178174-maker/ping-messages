import { describe, expect, it } from 'vitest';
import { hashPassword, needsRehash, verifyPassword } from '../src/lib/passwords.js';

describe('password hashing', () => {
  it('verifies a correct password', async () => {
    const hash = await hashPassword('correct horse battery staple');
    expect(await verifyPassword('correct horse battery staple', hash)).toBe(true);
  });

  it('rejects a wrong password', async () => {
    const hash = await hashPassword('correct horse battery staple');
    expect(await verifyPassword('Correct horse battery staple', hash)).toBe(false);
    expect(await verifyPassword('', hash)).toBe(false);
  });

  it('produces a different hash each time', async () => {
    // A per-password random salt is what stops identical passwords from
    // sharing a hash, which would otherwise leak that two accounts match.
    const a = await hashPassword('same password here');
    const b = await hashPassword('same password here');
    expect(a).not.toEqual(b);
    expect(await verifyPassword('same password here', a)).toBe(true);
    expect(await verifyPassword('same password here', b)).toBe(true);
  });

  it('stores parameters in the hash so they can be raised later', async () => {
    const hash = await hashPassword('another password');
    const [algorithm, n, r, p] = hash.split('$');
    expect(algorithm).toBe('scrypt');
    expect(Number(n)).toBeGreaterThanOrEqual(2 ** 17);
    expect(Number(r)).toBe(8);
    expect(Number(p)).toBe(1);
  });

  it('returns false rather than throwing on a malformed hash', async () => {
    // A corrupt row must be indistinguishable from a wrong password, both in
    // result and in error shape.
    for (const bad of ['', 'garbage', 'scrypt$1', 'bcrypt$1$2$3$4$5', 'scrypt$a$b$c$d$e']) {
      expect(await verifyPassword('anything', bad)).toBe(false);
    }
  });

  it('flags hashes below the current cost factor for rehashing', async () => {
    expect(needsRehash('scrypt$16384$8$1$c2FsdA==$aGFzaA==')).toBe(true);
    expect(needsRehash(await hashPassword('current parameters'))).toBe(false);
    expect(needsRehash('not-a-hash')).toBe(true);
  });

  it('normalises unicode so the same typed password always verifies', async () => {
    // "é" can be one code point or two; without NFKC the same keystrokes on a
    // different keyboard would fail to log in.
    const composed = 'passwörd-long-enough';
    const decomposed = composed.normalize('NFD');
    expect(composed).not.toEqual(decomposed);

    const hash = await hashPassword(composed);
    expect(await verifyPassword(decomposed, hash)).toBe(true);
  });
});
