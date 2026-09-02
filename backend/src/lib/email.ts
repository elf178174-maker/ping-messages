import { config } from '../config.js';

export interface EmailMessage {
  to: string;
  subject: string;
  text: string;
}

/**
 * Outbound email.
 *
 * The default driver prints to the server log rather than sending. That is a
 * deliberate development affordance, not a stub pretending to work: it means
 * the whole sign-up flow can be exercised end to end with no provider account,
 * and the log line says plainly that nothing was sent.
 *
 * Configuring `EMAIL_DRIVER=smtp` with `SMTP_URL` is the production path; the
 * SMTP transport is intentionally left to a deployment-chosen library so this
 * project does not bundle one nobody asked for. See docs/BACKEND.md.
 */
export async function sendEmail(message: EmailMessage): Promise<void> {
  if (config.EMAIL_DRIVER === 'console') {
    console.log(
      [
        '',
        '─────────────── EMAIL (not actually sent) ───────────────',
        `To:      ${message.to}`,
        `From:    ${config.EMAIL_FROM}`,
        `Subject: ${message.subject}`,
        '',
        message.text,
        '─────────────────────────────────────────────────────────',
        '',
      ].join('\n'),
    );
    return;
  }

  if (!config.SMTP_URL) {
    throw new Error('EMAIL_DRIVER=smtp requires SMTP_URL to be set.');
  }

  // A real SMTP transport is deployment-specific. Failing loudly here is
  // better than silently dropping a verification code a user is waiting for.
  throw new Error(
    'SMTP delivery is not implemented in this build. Set EMAIL_DRIVER=console for ' +
      'development, or wire an SMTP client into src/lib/email.ts. See docs/BACKEND.md.',
  );
}
