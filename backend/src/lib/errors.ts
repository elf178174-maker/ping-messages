/**
 * The error type every route throws.
 *
 * Carrying the HTTP status on the error means a handler can `throw new
 * ApiError(...)` from anywhere in its call stack and the single error hook in
 * app.ts turns it into the right response — no status plumbing through helpers.
 */
export class ApiError extends Error {
  readonly statusCode: number;
  readonly code: string;
  readonly fields?: Record<string, string>;
  readonly retryAfter?: number;

  constructor(
    statusCode: number,
    code: string,
    message: string,
    options: { fields?: Record<string, string>; retryAfter?: number } = {},
  ) {
    super(message);
    this.name = 'ApiError';
    this.statusCode = statusCode;
    this.code = code;
    this.fields = options.fields;
    this.retryAfter = options.retryAfter;
  }
}

export const badRequest = (message: string, fields?: Record<string, string>) =>
  new ApiError(400, 'bad_request', message, { fields });

export const unauthorized = (message = 'Authentication required') =>
  new ApiError(401, 'unauthorized', message);

export const forbidden = (message = 'Not allowed') => new ApiError(403, 'forbidden', message);

export const notFound = (what = 'Resource') => new ApiError(404, 'not_found', `${what} not found`);

export const conflict = (message: string, fields?: Record<string, string>) =>
  new ApiError(409, 'conflict', message, { fields });

export const tooLarge = (message = 'Payload too large') =>
  new ApiError(413, 'payload_too_large', message);

export const rateLimited = (retryAfter: number) =>
  new ApiError(429, 'rate_limited', 'Too many requests', { retryAfter });

export const serverError = (message = 'Internal error') =>
  new ApiError(500, 'internal_error', message);
