import { describe, expect, it } from 'vitest';
import { directPairKey, newInviteCode, newVerificationCode, sha256 } from '../src/lib/ids.js';

describe('identifiers', () => {
  it('derives the same direct-conversation key regardless of order', () => {
    // This symmetry is what the unique index relies on to stop two
    // conversations being created for one pair of people.
    expect(directPairKey('alice', 'bob')).toEqual(directPairKey('bob', 'alice'));
    expect(directPairKey('alice', 'bob')).not.toEqual(directPairKey('alice', 'carol'));
  });

  it('generates invite codes without ambiguous characters', () => {
    // 0/O and 1/l/I are excluded so a code read aloud cannot become a
    // different valid code.
    for (let i = 0; i < 200; i += 1) {
      const code = newInviteCode();
      expect(code).toHaveLength(12);
      expect(code).not.toMatch(/[0O1lI]/);
    }
  });

  it('generates distinct invite codes', () => {
    const codes = new Set(Array.from({ length: 500 }, () => newInviteCode()));
    expect(codes.size).toBe(500);
  });

  it('generates six-digit verification codes including leading zeros', () => {
    for (let i = 0; i < 500; i += 1) {
      expect(newVerificationCode()).toMatch(/^\d{6}$/);
    }
  });

  it('hashes deterministically', () => {
    expect(sha256('ping')).toEqual(sha256('ping'));
    expect(sha256('ping')).not.toEqual(sha256('pong'));
    expect(sha256('ping')).toHaveLength(64);
  });
});
