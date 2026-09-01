import { config as loadEnv } from 'dotenv';
import { z } from 'zod';

loadEnv();

/**
 * Configuration, validated once at boot.
 *
 * Failing to start on bad configuration is deliberate: a server that boots with
 * a missing JWT secret and only fails at the first login is far harder to
 * diagnose than one that refuses to start and says which variable is wrong.
 */
const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(8080),
  HOST: z.string().default('0.0.0.0'),
  PUBLIC_BASE_URL: z.string().url().default('http://localhost:8080'),

  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),

  // 32 characters is the floor for an HS256 key that is not trivially
  // brute-forceable. The two secrets must differ so an access token can never
  // be replayed as a refresh token.
  JWT_ACCESS_SECRET: z.string().min(32, 'JWT_ACCESS_SECRET must be at least 32 characters'),
  JWT_REFRESH_SECRET: z.string().min(32, 'JWT_REFRESH_SECRET must be at least 32 characters'),
  ACCESS_TOKEN_TTL_SECONDS: z.coerce.number().int().positive().default(900),
  REFRESH_TOKEN_TTL_DAYS: z.coerce.number().int().positive().default(60),

  MEDIA_DRIVER: z.enum(['local', 's3']).default('local'),
  MEDIA_LOCAL_DIR: z.string().default('./storage'),
  MEDIA_MAX_BYTES: z.coerce.number().int().positive().default(100 * 1024 * 1024),

  S3_ENDPOINT: z.string().optional(),
  S3_REGION: z.string().default('auto'),
  S3_BUCKET: z.string().optional(),
  S3_ACCESS_KEY_ID: z.string().optional(),
  S3_SECRET_ACCESS_KEY: z.string().optional(),

  EMAIL_DRIVER: z.enum(['console', 'smtp']).default('console'),
  EMAIL_FROM: z.string().default('no-reply@ping.example'),
  SMTP_URL: z.string().optional(),

  STUN_SERVERS: z.string().default(''),
  TURN_URL: z.string().optional(),
  TURN_USERNAME: z.string().optional(),
  TURN_CREDENTIAL: z.string().optional(),

  RATE_LIMIT_MAX: z.coerce.number().int().positive().default(300),
  RATE_LIMIT_WINDOW: z.string().default('1 minute'),
});

function load() {
  const parsed = schema.safeParse(process.env);
  if (!parsed.success) {
    const issues = parsed.error.issues
      .map((issue) => `  - ${issue.path.join('.')}: ${issue.message}`)
      .join('\n');
    throw new Error(`Invalid configuration:\n${issues}\n\nSee backend/.env.example.`);
  }

  const value = parsed.data;

  if (value.JWT_ACCESS_SECRET === value.JWT_REFRESH_SECRET) {
    throw new Error('JWT_ACCESS_SECRET and JWT_REFRESH_SECRET must be different values.');
  }

  if (value.MEDIA_DRIVER === 's3' && (!value.S3_BUCKET || !value.S3_ACCESS_KEY_ID)) {
    throw new Error('MEDIA_DRIVER=s3 requires S3_BUCKET and S3_ACCESS_KEY_ID.');
  }

  return value;
}

export const config = load();

export type Config = typeof config;

export const isProduction = config.NODE_ENV === 'production';
export const isTest = config.NODE_ENV === 'test';

/** ICE servers handed to clients so WebRTC can find a route. */
export function iceServers(): Array<{ urls: string[]; username?: string; credential?: string }> {
  const servers: Array<{ urls: string[]; username?: string; credential?: string }> = [];

  const stun = config.STUN_SERVERS.split(',').map((s) => s.trim()).filter(Boolean);
  if (stun.length > 0) servers.push({ urls: stun });

  // TURN is what makes calls work behind symmetric NAT — roughly 10-20% of
  // networks. Without it, those users get a call that rings and never connects.
  if (config.TURN_URL) {
    servers.push({
      urls: [config.TURN_URL],
      username: config.TURN_USERNAME,
      credential: config.TURN_CREDENTIAL,
    });
  }

  return servers;
}

export const callingEnabled = () => iceServers().length > 0;
