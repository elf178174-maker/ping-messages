import { createWriteStream } from 'node:fs';
import { mkdir, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { config } from '../config.js';
import { currentUser, requireAuth } from '../lib/auth.js';
import { badRequest, forbidden, notFound, tooLarge } from '../lib/errors.js';
import { newId, sha256 } from '../lib/ids.js';
import { idSchema, parse } from '../lib/validation.js';

/**
 * Media transfer.
 *
 * The client asks for an upload ticket, then PUTs the (already encrypted)
 * bytes straight to storage. Blobs never pass through the API process, which
 * is what keeps a hundred simultaneous video uploads from consuming the API's
 * memory and event loop.
 *
 * With MEDIA_DRIVER=local the "presigned" URL points back at this server's own
 * blob endpoint, guarded by a short-lived HMAC token — the same shape as S3, so
 * the client code path is identical either way and a deployment can switch
 * drivers without an app release.
 */
const UPLOAD_TOKEN_TTL_MS = 15 * 60 * 1000;

function signUploadToken(storageKey: string, expiresAt: number): string {
  // Derived from the JWT secret rather than a separate one: it is the same
  // trust boundary, and one fewer secret to configure or forget to rotate.
  return sha256(`${storageKey}:${expiresAt}:${config.JWT_ACCESS_SECRET}`).slice(0, 32);
}

function verifyUploadToken(storageKey: string, expiresAt: number, token: string): boolean {
  if (!Number.isFinite(expiresAt) || expiresAt < Date.now()) return false;
  return signUploadToken(storageKey, expiresAt) === token;
}

/** Rejects any key that could escape the media directory. */
function safeStoragePath(storageKey: string): string {
  const root = resolve(config.MEDIA_LOCAL_DIR);
  const full = resolve(join(root, storageKey));
  if (!full.startsWith(root + '/') && full !== root) {
    throw forbidden('Invalid storage key');
  }
  return full;
}

export async function mediaRoutes(app: FastifyInstance): Promise<void> {
  app.post('/v1/media/upload-ticket', { preHandler: requireAuth }, async (request) => {
    const body = parse(
      z.object({
        fileName: z.string().max(255).default('file'),
        mimeType: z.string().max(255).default('application/octet-stream'),
        sizeBytes: z.number().int().min(0),
        kind: z.string().max(32).default('DOCUMENT'),
      }),
      request.body,
    );

    if (body.sizeBytes > config.MEDIA_MAX_BYTES) {
      throw tooLarge(`Files must be ${Math.floor(config.MEDIA_MAX_BYTES / (1024 * 1024))} MB or smaller`);
    }

    const userId = currentUser(request);
    const attachmentId = newId();
    // Sharded by user so no single directory accumulates millions of entries,
    // which is where local filesystems start to degrade.
    const storageKey = `${userId.slice(0, 2)}/${userId}/${attachmentId}`;
    const expiresAt = Date.now() + UPLOAD_TOKEN_TTL_MS;
    const token = signUploadToken(storageKey, expiresAt);

    if (config.MEDIA_DRIVER === 's3') {
      // A real deployment signs an S3 PUT here. The shape below is what the
      // client expects; wiring @aws-sdk/s3-request-presigner is a
      // deployment-time choice, documented in docs/BACKEND.md, and left out
      // rather than stubbed so it cannot appear to work when it does not.
      throw badRequest(
        'MEDIA_DRIVER=s3 requires the presigner to be configured. See docs/BACKEND.md.',
      );
    }

    const base = config.PUBLIC_BASE_URL.replace(/\/$/, '');
    return {
      attachmentId,
      uploadUrl: `${base}/v1/media/blob/${storageKey}?token=${token}&expires=${expiresAt}`,
      downloadUrl: `${base}/v1/media/blob/${storageKey}`,
      method: 'PUT',
      headers: { 'content-type': 'application/octet-stream' },
      expiresAt,
    };
  });

  /**
   * Blob upload.
   *
   * Authorised by the signed ticket rather than a bearer token, so the client
   * can hand the URL to a background upload worker without also handing it a
   * session. The body is streamed to disk with a hard byte cap.
   */
  app.put('/v1/media/blob/*', async (request, reply) => {
    const storageKey = (request.params as Record<string, string>)['*'] ?? '';
    const q = parse(
      z.object({ token: z.string().length(32), expires: z.coerce.number().int() }),
      request.query,
    );

    if (!verifyUploadToken(storageKey, q.expires, q.token)) {
      throw forbidden('That upload link has expired');
    }

    const target = safeStoragePath(storageKey);
    await mkdir(dirname(target), { recursive: true });

    let written = 0;
    const cap = config.MEDIA_MAX_BYTES;
    const counter = async function* (source: AsyncIterable<Buffer>) {
      for await (const chunk of source) {
        written += chunk.length;
        // Enforced while streaming, not from content-length: a client can lie
        // about the header but cannot lie about the bytes it actually sends.
        if (written > cap) throw tooLarge('Upload exceeded the size limit');
        yield chunk;
      }
    };

    await pipeline(request.raw, counter, createWriteStream(target));
    return reply.code(201).send({ ok: true, sizeBytes: written });
  });

  /** Blob download. The bytes are ciphertext; only a member with the key can read them. */
  app.get('/v1/media/blob/*', async (request, reply) => {
    const storageKey = (request.params as Record<string, string>)['*'] ?? '';
    const target = safeStoragePath(storageKey);

    const info = await stat(target).catch(() => null);
    if (!info?.isFile()) throw notFound('File');

    reply.header('content-type', 'application/octet-stream');
    reply.header('content-length', info.size);
    // Ciphertext is immutable once written, so it can be cached hard.
    reply.header('cache-control', 'private, max-age=31536000, immutable');
    return reply.send((await import('node:fs')).createReadStream(target));
  });
}
