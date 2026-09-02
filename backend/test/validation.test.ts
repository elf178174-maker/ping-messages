import { describe, expect, it } from 'vitest';
import { ApiError } from '../src/lib/errors.js';
import {
  assertPasswordAcceptable,
  emailSchema,
  parse,
  passwordSchema,
  usernameSchema,
} from '../src/lib/validation.js';

describe('server-side validation', () => {
  it('accepts valid emails and rejects malformed ones', () => {
    expect(emailSchema.safeParse('ada@example.com').success).toBe(true);
    expect(emailSchema.safeParse('ada+ping@sub.example.co.uk').success).toBe(true);
    expect(emailSchema.safeParse('ada@example').success).toBe(false);
    expect(emailSchema.safeParse('nope').success).toBe(false);
  });

  it('normalises usernames to lowercase', () => {
    expect(usernameSchema.parse('  AdaLovelace ')).toBe('adalovelace');
    expect(usernameSchema.safeParse('ad').success).toBe(false);
    expect(usernameSchema.safeParse('ada lovelace').success).toBe(false);
    expect(usernameSchema.safeParse('a'.repeat(25)).success).toBe(false);
  });

  it('enforces the same minimum password length as the client', () => {
    expect(passwordSchema.safeParse('123456789').success).toBe(false);
    expect(passwordSchema.safeParse('1234567890').success).toBe(true);
  });

  it('rejects passwords from the blocklist', () => {
    expect(() => assertPasswordAcceptable('password123')).toThrow(ApiError);
    expect(() => assertPasswordAcceptable('aaaaaaaaaaaa')).toThrow(ApiError);
    expect(() => assertPasswordAcceptable('a perfectly fine passphrase')).not.toThrow();
  });

  it('reports validation failures per field so the client can attach them', async () => {
    // The client renders each message under its own input; a single flat
    // string would leave it unable to.
    try {
      const { z } = await import('zod');
      parse(z.object({ email: emailSchema, username: usernameSchema }), {
        email: 'bad',
        username: 'x',
      });
      throw new Error('expected parse to throw');
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError);
      const apiError = error as ApiError;
      expect(apiError.statusCode).toBe(400);
      expect(Object.keys(apiError.fields ?? {})).toEqual(
        expect.arrayContaining(['email', 'username']),
      );
    }
  });
});
