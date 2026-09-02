# Backend

Fastify 5 over PostgreSQL, in TypeScript. This covers running it, configuring it, the API it
exposes, and what a production deployment needs that this repository does not ship.

---

## Running it

```bash
cd backend
cp .env.example .env

# The two JWT secrets must be different and at least 32 characters
echo "JWT_ACCESS_SECRET=$(openssl rand -base64 48)"  >> .env
echo "JWT_REFRESH_SECRET=$(openssl rand -base64 48)" >> .env

# Point DATABASE_URL at Postgres 14 or newer, then
npm install
npm run migrate
npm run dev
```

`npm run migrate` is idempotent: every statement is `IF NOT EXISTS` and applied migrations are
recorded in `schema_migrations`, so re-running it on a live database is a no-op.

With `EMAIL_DRIVER=console` — the default — verification codes and reset codes are **printed to
the server log** instead of emailed. That is what makes it possible to register an account and
use the app with no email provider configured at all.

### Pointing the app at it

```bash
cd android
./gradlew :app:assembleDebug -PpingApiBaseUrl=http://10.0.2.2:8080/
```

`10.0.2.2` is the host machine as seen from the Android emulator; on a physical device use your
machine's LAN address. The address can also be changed at runtime in **Settings ▸ Advanced**,
which is the easier path when the APK came from CI.

---

## Configuration

Every value comes from the environment. Nothing secret is committed, and `.env` is git-ignored.

| Variable | Default | Notes |
| --- | --- | --- |
| `NODE_ENV` | `development` | `production` tightens CORS and enables `trustProxy` |
| `PORT` / `HOST` | `8080` / `0.0.0.0` | |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | Used to build invite links and media URLs |
| `DATABASE_URL` | — | **Required.** Postgres 14+ |
| `JWT_ACCESS_SECRET` | — | **Required.** ≥32 chars |
| `JWT_REFRESH_SECRET` | — | **Required.** ≥32 chars, different from the access secret |
| `ACCESS_TOKEN_TTL_SECONDS` | `900` | |
| `REFRESH_TOKEN_TTL_DAYS` | `60` | |
| `MEDIA_DRIVER` | `local` | `local` or `s3` |
| `MEDIA_LOCAL_DIR` | `./storage` | Where `local` writes blobs |
| `MEDIA_MAX_BYTES` | `104857600` | Enforced on the bytes, not the header |
| `S3_ENDPOINT` / `S3_REGION` / `S3_BUCKET` / `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY` | — | Only for `MEDIA_DRIVER=s3`; works with S3, R2 and MinIO |
| `EMAIL_DRIVER` | `console` | `console` prints codes to the log; `smtp` needs `SMTP_URL` |
| `EMAIL_FROM` | `no-reply@ping.example` | |
| `SMTP_URL` | — | |
| `STUN_SERVERS` | `stun:stun.l.google.com:19302` | Comma-separated; served to clients at `/v1/calls/config` |
| `TURN_URL` / `TURN_USERNAME` / `TURN_CREDENTIAL` | — | See [CALLS.md](CALLS.md) |
| `RATE_LIMIT_MAX` | `300` | Per window |
| `RATE_LIMIT_WINDOW` | `1 minute` | |

Boot validates all of it with zod. A missing or malformed value stops the process with a
message naming the variable.

---

## API

All routes are under `/v1`. Everything except `/health` and the `auth` routes below requires a
`Authorization: Bearer <access token>` header.

### Auth — no bearer token required

```
POST   /v1/auth/register            POST   /v1/auth/login
POST   /v1/auth/refresh             POST   /v1/auth/logout
POST   /v1/auth/verify-email        POST   /v1/auth/resend-code
POST   /v1/auth/forgot-password     POST   /v1/auth/reset-password
POST   /v1/auth/change-password     POST   /v1/auth/two-step
GET    /v1/auth/username-available
```

### Users, contacts, blocks

```
GET    /v1/me                       PATCH  /v1/me
DELETE /v1/me                       PUT    /v1/me/device-key
GET    /v1/me/privacy               PUT    /v1/me/privacy
GET    /v1/me/devices               DELETE /v1/me/devices        (all others)
DELETE /v1/me/devices/:id
GET    /v1/users                    GET    /v1/users/:id
GET    /v1/users/by-username/:username
GET    /v1/contacts                 POST   /v1/contacts/:id
DELETE /v1/contacts/:id             POST   /v1/contacts/discover
GET    /v1/blocks                   POST   /v1/blocks
DELETE /v1/blocks/:id               POST   /v1/reports
```

`POST /v1/contacts/discover` takes **salted hashes** of phone numbers, never numbers.

### Conversations and messages

```
GET    /v1/conversations            POST   /v1/conversations
GET    /v1/conversations/:id        DELETE /v1/conversations/:id
GET    /v1/conversations/:id/messages
PUT    /v1/conversations/:id/disappearing
PUT    /v1/conversations/:id/pinned-message
POST   /v1/messages                 PATCH  /v1/messages/:id
POST   /v1/messages/:id/delete      POST   /v1/messages/:id/reactions
POST   /v1/messages/:id/star        POST   /v1/messages/:id/vote
POST   /v1/receipts/read            GET    /v1/search
GET    /v1/sync
```

`POST /v1/messages` is idempotent on the client-generated id: sending the same id twice returns
the same message rather than creating a second one.

`GET /v1/sync` is the gap-filling endpoint — a client says which sequence it has reached per
conversation and gets exactly what it is missing.

### Groups

```
POST   /v1/groups                   GET    /v1/groups/:id
PATCH  /v1/groups/:id               POST   /v1/groups/:id/members
DELETE /v1/groups/:id/members/:userId
PUT    /v1/groups/:id/members/:userId/role
GET    /v1/groups/:id/invite        POST   /v1/groups/:id/invite/reset
POST   /v1/groups/join/:code        POST   /v1/groups/:id/leave
```

Invite codes are returned only to admins. Role hierarchy and the permission matrix are enforced
here, not only hidden in the app: a hand-rolled request from a member cannot post to a
send-restricted group.

When an owner leaves, ownership succeeds to the longest-standing admin, or the longest-standing
member if there are none.

### Media, status, calls

```
POST   /v1/media/upload-ticket      PUT    /v1/media/blob/*
GET    /v1/media/blob/*
GET    /v1/status                   POST   /v1/status
POST   /v1/status/:id/view          DELETE /v1/status/:id
GET    /v1/calls                    POST   /v1/calls
POST   /v1/calls/:id/end            DELETE /v1/calls
GET    /v1/calls/config
```

Uploads are streamed straight to storage against a signed ticket, so blobs never buffer in the
API process, and the size cap is enforced on the bytes as they arrive rather than on a
`Content-Length` header a client controls.

Status audience filtering happens **in SQL**, not after the fetch.

### Realtime

```
GET /v1/realtime      (WebSocket upgrade)
```

Carries message events, receipts, typing, presence and WebRTC signalling. Signalling payloads
are relayed without inspection — the server is a post box for SDP and ICE, not a participant.

---

## Errors

One shape for every error:

```json
{ "error": "not_found", "message": "…", "code": "not_found", "fields": {}, "retryAfter": null }
```

`4xx` bodies carry a usable message. `5xx` bodies never do: an internal message can leak table
names, file paths, or query fragments, so the detail goes to the log and the client gets
"Something went wrong on our side."

Rate limiting is keyed by user when authenticated and by IP otherwise, so one user behind a
shared NAT cannot exhaust everybody else's budget.

---

## Tests

```bash
npm run typecheck
npm test                 # 53 tests
npm run build
```

36 of those tests are end-to-end: they run the real Fastify app against a real Postgres via
`app.inject()`, with **no mocks**. That is what makes them worth having — they caught a real
design bug in the group-privacy default that no unit test would have.

They skip automatically when `DATABASE_URL` is unset, so `npm test` works without a database
(and the CI workflow provides one).

---

## Deploying it

What is here is a single stateless process plus Postgres. To run it for real you need, in
rough order of importance:

1. **TLS in front of it.** The app refuses cleartext HTTP outside loopback, which means a
   reverse proxy (Caddy, nginx, a load balancer) terminating TLS is not optional.
2. **`MEDIA_DRIVER=s3`.** The `local` driver writes to the container's filesystem, which is the
   wrong place for user media in any deployment with more than one instance or any redeploys.
3. **A real email transport.** `EMAIL_DRIVER=smtp` with `SMTP_URL`. Until then, verification
   codes are in the log.
4. **Pub/sub for the realtime hub, if you run more than one instance.** `realtime/hub.ts` keeps
   sockets in a process-local map. Two replicas each reach only their own clients — which looks
   exactly like "messages sometimes do not arrive until you reopen the app". The fix is to
   publish fan-out through Redis or Postgres `LISTEN`/`NOTIFY` and have each instance deliver to
   its local sockets. The hub's interface is the only thing that changes.
5. **Backups of Postgres.** Message bodies are ciphertext, so a leaked backup is far less bad
   than usual — but losing it still loses everyone's history.
