import cors from '@fastify/cors';
import rateLimit from '@fastify/rate-limit';
import websocket from '@fastify/websocket';
import Fastify, { type FastifyInstance } from 'fastify';
import { config, isProduction, isTest } from './config.js';
import { ApiError } from './lib/errors.js';
import { authRoutes } from './routes/auth.js';
import { callRoutes } from './routes/calls.js';
import { conversationRoutes } from './routes/conversations.js';
import { groupRoutes } from './routes/groups.js';
import { mediaRoutes } from './routes/media.js';
import { messageRoutes } from './routes/messages.js';
import { statusRoutes } from './routes/status.js';
import { syncRoutes } from './routes/sync.js';
import { userRoutes } from './routes/users.js';
import { hub } from './realtime/hub.js';
import { realtimeGateway } from './realtime/gateway.js';

export async function buildApp(): Promise<FastifyInstance> {
  const app = Fastify({
    logger: isTest
      ? false
      : {
          level: isProduction ? 'info' : 'debug',
          // Request bodies carry ciphertext and headers carry bearer tokens,
          // so neither is ever logged.
          redact: ['req.headers.authorization', 'req.headers.cookie'],
        },
    // The media upload route streams its own body; a global body limit would
    // reject large uploads before they reach it.
    bodyLimit: 2 * 1024 * 1024,
    trustProxy: isProduction,
  });

  await app.register(cors, {
    // A mobile client sends no Origin, so CORS is only relevant to a future
    // web client. Permissive in development, explicit in production.
    origin: isProduction ? [config.PUBLIC_BASE_URL] : true,
    credentials: true,
  });

  await app.register(rateLimit, {
    max: config.RATE_LIMIT_MAX,
    timeWindow: config.RATE_LIMIT_WINDOW,
    // Keyed by user when authenticated, so one user on a shared NAT cannot
    // exhaust everybody else's budget.
    keyGenerator: (request) => request.userId ?? request.ip,
    allowList: () => isTest,
  });

  await app.register(websocket, {
    options: {
      maxPayload: 1 * 1024 * 1024,
      // Rejects an upgrade before the socket is established when the payload
      // would be oversized, rather than after.
      clientTracking: false,
    },
  });

  // ---- Error handling -----------------------------------------------------

  app.setErrorHandler((rawError: unknown, request, reply) => {
    const error = rawError as Error & { statusCode?: number };

    if (error instanceof ApiError) {
      if (error.retryAfter) reply.header('retry-after', String(error.retryAfter));
      return reply.code(error.statusCode).send({
        error: error.code,
        message: error.message,
        code: error.code,
        fields: error.fields,
        retryAfter: error.retryAfter,
      });
    }

    // Fastify's own validation and rate-limit errors carry a statusCode.
    const statusCode = error.statusCode;
    if (typeof statusCode === 'number' && statusCode < 500) {
      return reply.code(statusCode).send({
        error: 'bad_request',
        message: error.message,
      });
    }

    // Anything unexpected is logged in full but reported opaquely: an internal
    // message can leak table names, file paths or query fragments.
    request.log.error({ err: error }, 'unhandled error');
    return reply.code(500).send({
      error: 'internal_error',
      message: 'Something went wrong on our side.',
    });
  });

  app.setNotFoundHandler((request, reply) =>
    reply.code(404).send({ error: 'not_found', message: `No route for ${request.method} ${request.url}` }),
  );

  // ---- Routes -------------------------------------------------------------

  app.get('/health', async () => ({
    ok: true,
    service: 'ping-backend',
    connections: hub.connectionCount,
    time: Date.now(),
  }));

  await app.register(authRoutes);
  await app.register(userRoutes);
  await app.register(conversationRoutes);
  await app.register(messageRoutes);
  await app.register(groupRoutes);
  await app.register(mediaRoutes);
  await app.register(statusRoutes);
  await app.register(callRoutes);
  await app.register(syncRoutes);
  await app.register(realtimeGateway);

  return app;
}
