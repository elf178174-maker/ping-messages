# Architecture

Ping is two programs: an Android app and a backend. This describes how each is put together and
why, with an emphasis on the decisions that would be expensive to reverse.

---

## The shape of the Android app

```
feature/…            Compose screens and their view-models, one package per area
  ↓ observes
domain/              Models and repository interfaces. No Android, no Room, no Retrofit.
  ↑ implemented by
data/                Room entities and DAOs, Retrofit APIs, the WebSocket client, mappers
core/                Cross-cutting machinery: crypto, network plumbing, media, notifications,
                     WorkManager jobs, the realtime service, backup, QR
ui/                  Design system, shared components, navigation graph
```

126 Kotlin files in one Gradle module. One module rather than five is a deliberate choice for a
project this size: module boundaries buy parallel compilation and enforced layering, and cost a
build-configuration file per module plus a `:core:x` prefix on every import. At this scale the
layering is enforced by the `domain` package having no Android imports, which is checkable by
reading it. If the app grows past the point where a clean build is painful, the seams are
already where the module boundaries would go — `domain` depends on nothing, `data` depends on
`domain`, `feature` depends on both.

### The one property that matters: the UI never awaits the network

```
Compose screen  →  ViewModel (StateFlow)  →  Repository  ─┬→  Room  (source of truth)
                                                          └→  Outbox → SyncWorker → API
```

Every mutation follows the same path. Take sending a message:

1. `MessageRepositoryImpl.send` generates the message id **on the device**.
2. In one Room transaction it writes the message row, updates the conversation's last-message
   pointer, and appends a row to the `outbox` table.
3. It asks `SyncTrigger` to run the sync worker, and returns.

The screen has already updated, because it observes Room. Nothing in a view-model asks "are we
online?" — the outbox is the answer. `SyncWorker` drains it when connectivity allows, with
full-jitter exponential backoff, and classifies each failure as transient (keep the row, retry)
or permanent (drop it, mark the message failed so the UI can offer a retry).

Two consequences worth naming:

- **Client-generated ids make sends idempotent.** The optimistic row and the server's
  acknowledgement are the same row, so a retry after an ambiguous timeout cannot duplicate a
  message. The server treats a repeated id as the same message.
- **Encryption happens at send time, not compose time.** A message queued offline for six hours
  is sealed to the recipient's key as it is *sent*, not as it was typed. Sealing at compose time
  would break every queued message whenever a recipient re-keyed a device.

### Paging and the transcript

The transcript is a Room `PagingSource` in a reverse-layout `LazyColumn`, so opening a
conversation reads a page rather than the history. Older pages come from the server by
**sequence number, not offset**: each conversation has a monotonic `seq` allocated inside the
send transaction, and paging asks for "everything before seq N". Offset paging over a table
that is being appended to skips and repeats rows; sequence paging cannot.

### Unread counts

Derived from a `last_read_seq` pointer rather than stored as a counter. A stored counter has to
be incremented and decremented by every code path that touches a message, and the bug where it
drifts from reality is unfixable without a recount. A pointer has one writer and the count is a
query.

### Realtime

`RealtimeClient` is a WebSocket with full-jitter exponential backoff capped at 60 seconds, an
application-level heartbeat, and subscriptions replayed on reconnect. The heartbeat is not
belt-and-braces: NAT and mobile radios routinely keep a TCP connection *open* long after it has
stopped carrying traffic, and only an application-level ping notices.

Inbound events do not go to the UI. `RealtimeEventApplier` writes them to Room, and one write
updates the chat list, the open transcript, the tab badge and the notification — because all
four observe the same rows.

---

## The shape of the backend

```
server.ts       Boots the app and listens
app.ts          Fastify instance: CORS, rate limiting, error handling, route registration
config.ts       Environment validated with zod at boot
routes/         One file per resource: auth, users, conversations, messages, groups,
                media, status, calls, sync
realtime/       gateway.ts (the WebSocket route) and hub.ts (in-memory fan-out)
lib/            auth, tokens, passwords, presence projection, validation, ids, errors, email
db/             Pool, migration runner, and the SQL schema
```

24 TypeScript files, ESM, `strict` plus `noUncheckedIndexedAccess`.

Configuration is validated **at boot**, not at first use. A missing `JWT_ACCESS_SECRET` makes
the process fail to start with a message naming the variable, rather than failing at somebody's
first login attempt an hour later.

### The database

25 tables, 56 indexes, 36 foreign keys. Every index matches a query the code actually runs;
covering indexes are shaped like the exact orderings the app asks for. Cascade deletes mean
removing a conversation is one statement rather than a nine-table transaction that can be got
wrong.

Message bodies are ciphertext and are **deliberately not indexed**. There is nothing useful to
index: the server cannot read them. Search happens on the device, over a Room FTS4 index, which
is the only place a plaintext index can honestly exist.

`direct_conversation_keys.pair_key` is the sorted-and-joined pair of user ids with a unique
index on it. Two devices opening the same one-to-one chat at the same moment therefore converge
on one conversation instead of creating two.

### Sequence allocation

A conversation's `next_seq` is bumped inside the same transaction that inserts the message, so
two concurrent sends cannot be handed the same sequence. This is the piece that makes gap-free
sync possible: a client that has seq 1..40 and asks for "everything after 40" gets exactly what
it is missing.

---

## What is not here, and where it would go

- **A second backend instance.** `hub.ts` holds sockets in a process-local map, so two replicas
  would each only reach their own clients. Scaling out means replacing the hub's fan-out with
  Redis pub/sub or Postgres `LISTEN`/`NOTIFY`; the hub's interface is the seam and nothing else
  changes. See [BACKEND.md](BACKEND.md).
- **Push notifications.** Messages arrive over the app's own socket while it is running, and
  missed messages are fetched on next launch. FCM needs a `google-services.json`, which cannot
  be committed.
- **A group-call SFU.** Group calls are a full mesh, which is fine to about four people. See
  [CALLS.md](CALLS.md).
- **Forward secrecy.** The crypto layer's interface is shaped so a libsignal implementation can
  replace the current one without touching a caller. See [SECURITY.md](SECURITY.md).
