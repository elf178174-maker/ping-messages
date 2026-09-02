import { z } from 'zod';
import { badRequest } from './errors.js';

/**
 * Server-side validation.
 *
 * These rules intentionally mirror the Android client's `AuthValidation`, but
 * they are the ones that count: the client's copy exists to give fast feedback,
 * and a modified client can send anything. Every route validates here before
 * touching the database.
 */

export const emailSchema = z
  .string()
  .trim()
  .min(5)
  .max(254)
  .regex(/^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/, 'Enter a valid email address');

export const usernameSchema = z
  .string()
  .trim()
  .toLowerCase()
  .regex(/^[a-z0-9_]{3,24}$/, '3–24 letters, numbers or underscores');

export const passwordSchema = z
  .string()
  .min(10, 'Use at least 10 characters')
  .max(200, 'That password is too long');

export const displayNameSchema = z.string().trim().min(1).max(40);
export const aboutSchema = z.string().trim().max(140);
export const codeSchema = z.string().trim().regex(/^\d{6}$/, 'Enter the 6-digit code');
export const pinSchema = z.string().regex(/^\d{6}$/, 'PIN must be six digits');
export const idSchema = z.string().min(1).max(64);

export const audienceSchema = z.enum(['EVERYONE', 'CONTACTS', 'NOBODY']);
export const permissionSchema = z.enum(['EVERYONE', 'ADMINS_ONLY']);
export const roleSchema = z.enum(['MEMBER', 'ADMIN', 'OWNER']);

/**
 * Parses [input] against [schema], converting a Zod failure into the API's
 * field-keyed 400 so the client can attach each message to the right input.
 */
export function parse<T extends z.ZodTypeAny>(schema: T, input: unknown): z.infer<T> {
  const result = schema.safeParse(input);
  if (result.success) return result.data;

  const fields: Record<string, string> = {};
  for (const issue of result.error.issues) {
    const key = issue.path.join('.') || '_';
    if (!fields[key]) fields[key] = issue.message;
  }
  throw badRequest('Please check the details and try again', fields);
}

/**
 * A small blocklist of passwords that top every breach corpus.
 *
 * Lives on the server so it can grow without shipping an app release. A
 * production deployment should replace this with a check against a real
 * corpus (for example the Pwned Passwords k-anonymity API).
 */
const COMMON_PASSWORDS = new Set([
  'password', 'password1', 'password123', 'passw0rd', '12345678', '123456789',
  '1234567890', 'qwertyuiop', 'letmein123', 'iloveyou1', 'adminadmin', 'welcome123',
  'changeme123', 'qwerty12345', 'abc12345678', 'football123', 'monkey12345',
]);

export function assertPasswordAcceptable(password: string): void {
  if (COMMON_PASSWORDS.has(password.toLowerCase())) {
    throw badRequest('That password is too easy to guess', {
      password: 'That password is too easy to guess',
    });
  }
  if (new Set(password).size === 1) {
    throw badRequest('That password is too easy to guess', {
      password: 'That password is too easy to guess',
    });
  }
}
