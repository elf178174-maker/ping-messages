-- ---------------------------------------------------------------------------
-- Ping initial schema
--
-- Design principles applied throughout:
--
--  * IDs are client-generatable UUIDs (text), so a message can be created
--    offline and keep the same identity once it reaches the server. That is
--    what makes retries idempotent.
--  * Message bodies are ciphertext. The server stores and routes them but
--    cannot read them, so there is deliberately no index on `body` and no
--    server-side message search.
--  * Every foreign key that represents ownership cascades, so deleting an
--    account or a conversation is a single statement and cannot orphan rows.
--  * Timestamps are BIGINT epoch milliseconds rather than TIMESTAMPTZ: the
--    client and server compare them directly, and a timezone-aware type
--    invites accidental local-time conversion on one side only.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS users (
    id                TEXT PRIMARY KEY,
    email             TEXT        NOT NULL,
    email_normalised  TEXT        NOT NULL,
    email_verified    BOOLEAN     NOT NULL DEFAULT FALSE,
    password_hash     TEXT        NOT NULL,
    username          TEXT        NOT NULL,
    display_name      TEXT        NOT NULL,
    about             TEXT        NOT NULL DEFAULT '',
    avatar_url        TEXT,
    -- Stored only if the user chooses to add one; never returned to peers.
    phone_number      TEXT,
    -- Salted hash of the phone number, used for contact discovery so the
    -- server never needs the number itself to answer a match query.
    phone_hash        TEXT,
    two_step_pin_hash TEXT,
    is_deleted        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        BIGINT      NOT NULL,
    updated_at        BIGINT      NOT NULL
);

-- Case-insensitive uniqueness, enforced on the normalised columns so a lookup
-- can use the index rather than calling lower() at query time.
CREATE UNIQUE INDEX IF NOT EXISTS users_email_key    ON users (email_normalised) WHERE is_deleted = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS users_username_key ON users (lower(username))  WHERE is_deleted = FALSE;
CREATE INDEX        IF NOT EXISTS users_phone_hash   ON users (phone_hash)       WHERE phone_hash IS NOT NULL;
CREATE INDEX        IF NOT EXISTS users_display_name ON users (lower(display_name));

-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS user_privacy (
    user_id           TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    last_seen         TEXT    NOT NULL DEFAULT 'CONTACTS',
    online_status     TEXT    NOT NULL DEFAULT 'EVERYONE',
    profile_photo     TEXT    NOT NULL DEFAULT 'CONTACTS',
    about             TEXT    NOT NULL DEFAULT 'CONTACTS',
    status            TEXT    NOT NULL DEFAULT 'CONTACTS',
    groups            TEXT    NOT NULL DEFAULT 'CONTACTS',
    calls             TEXT    NOT NULL DEFAULT 'EVERYONE',
    read_receipts     BOOLEAN NOT NULL DEFAULT TRUE,
    typing_indicators BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS user_presence (
    user_id      TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    is_online    BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at BIGINT
);

-- ---------------------------------------------------------------------------
-- Devices and sessions
--
-- One row per signed-in device. The refresh token is stored hashed, so a
-- database leak does not hand an attacker working sessions.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS devices (
    id                 TEXT PRIMARY KEY,
    user_id            TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name               TEXT   NOT NULL,
    platform           TEXT   NOT NULL,
    -- The device's public key. Peers encrypt to this; the server only relays it.
    public_key         TEXT,
    refresh_token_hash TEXT,
    refresh_expires_at BIGINT,
    ip_country         TEXT,
    last_active_at     BIGINT NOT NULL,
    created_at         BIGINT NOT NULL,
    revoked_at         BIGINT
);

CREATE INDEX IF NOT EXISTS devices_user      ON devices (user_id) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS devices_refresh   ON devices (refresh_token_hash) WHERE refresh_token_hash IS NOT NULL;

-- Short-lived one-time codes: email verification and password reset.
CREATE TABLE IF NOT EXISTS verification_codes (
    id         TEXT PRIMARY KEY,
    user_id    TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose    TEXT   NOT NULL,
    code_hash  TEXT   NOT NULL,
    expires_at BIGINT NOT NULL,
    consumed   BOOLEAN NOT NULL DEFAULT FALSE,
    attempts   INT    NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS verification_codes_lookup ON verification_codes (user_id, purpose) WHERE consumed = FALSE;
CREATE INDEX IF NOT EXISTS verification_codes_expiry ON verification_codes (expires_at);

-- ---------------------------------------------------------------------------
-- Contacts, blocks and reports
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS contacts (
    user_id    TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    contact_id TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, contact_id)
);

CREATE INDEX IF NOT EXISTS contacts_by_user ON contacts (user_id);

CREATE TABLE IF NOT EXISTS blocks (
    user_id    TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, blocked_id)
);

CREATE INDEX IF NOT EXISTS blocks_by_user ON blocks (user_id);

CREATE TABLE IF NOT EXISTS reports (
    id            TEXT PRIMARY KEY,
    reporter_id   TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reported_id   TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason        TEXT   NOT NULL,
    note          TEXT,
    -- Message ids the reporter attached as evidence. The bodies stay encrypted;
    -- moderation can only act on what the reporter chooses to forward.
    message_ids   TEXT[] NOT NULL DEFAULT '{}',
    status        TEXT   NOT NULL DEFAULT 'OPEN',
    created_at    BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS reports_open ON reports (status, created_at);

-- ---------------------------------------------------------------------------
-- Conversations
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS conversations (
    id                    TEXT PRIMARY KEY,
    type                  TEXT   NOT NULL CHECK (type IN ('DIRECT', 'GROUP')),
    -- Monotonic per-conversation counter. Clients sync by asking for
    -- everything after the highest sequence they hold, which is reliable even
    -- when device clocks disagree.
    last_seq              BIGINT NOT NULL DEFAULT 0,
    disappearing_after_ms BIGINT,
    pinned_message_id     TEXT,
    created_at            BIGINT NOT NULL,
    updated_at            BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS conversations_updated ON conversations (updated_at DESC);

CREATE TABLE IF NOT EXISTS conversation_members (
    conversation_id  TEXT   NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id          TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role             TEXT   NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('MEMBER', 'ADMIN', 'OWNER')),
    joined_at        BIGINT NOT NULL,
    -- Highest sequence this member has read; the unread count is derived from
    -- it rather than stored, so it can never drift.
    last_read_seq    BIGINT NOT NULL DEFAULT 0,
    left_at          BIGINT,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX IF NOT EXISTS conversation_members_user ON conversation_members (user_id) WHERE left_at IS NULL;

-- Finds the existing one-to-one conversation between two people without a
-- self-join over every membership row.
CREATE TABLE IF NOT EXISTS direct_conversation_keys (
    conversation_id TEXT PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
    -- The two user ids, sorted and joined, so the pair maps to one key
    -- regardless of who initiated.
    pair_key        TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS direct_pair_key ON direct_conversation_keys (pair_key);

-- ---------------------------------------------------------------------------
-- Groups
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS groups (
    id                     TEXT PRIMARY KEY,
    conversation_id        TEXT   NOT NULL UNIQUE REFERENCES conversations(id) ON DELETE CASCADE,
    name                   TEXT   NOT NULL,
    description            TEXT   NOT NULL DEFAULT '',
    avatar_url             TEXT,
    created_by             TEXT   REFERENCES users(id) ON DELETE SET NULL,
    invite_code            TEXT,
    send_permission        TEXT   NOT NULL DEFAULT 'EVERYONE',
    edit_info_permission   TEXT   NOT NULL DEFAULT 'ADMINS_ONLY',
    add_members_permission TEXT   NOT NULL DEFAULT 'EVERYONE',
    created_at             BIGINT NOT NULL,
    updated_at             BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS groups_invite_code ON groups (invite_code) WHERE invite_code IS NOT NULL;
CREATE INDEX        IF NOT EXISTS groups_name        ON groups (lower(name));

-- ---------------------------------------------------------------------------
-- Messages
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS messages (
    id                   TEXT PRIMARY KEY,
    conversation_id      TEXT   NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id            TEXT   REFERENCES users(id) ON DELETE SET NULL,
    seq                  BIGINT NOT NULL,
    kind                 TEXT   NOT NULL DEFAULT 'TEXT',
    -- Ciphertext. Deliberately not indexed and never inspected server-side.
    body                 TEXT   NOT NULL DEFAULT '',
    is_encrypted         BOOLEAN NOT NULL DEFAULT TRUE,
    encryption_algorithm TEXT,
    reply_to_id          TEXT,
    forwarded_from       TEXT,
    mentions             TEXT[] NOT NULL DEFAULT '{}',
    -- Structured, non-secret payloads (poll options, system events).
    metadata             JSONB,
    expires_at           BIGINT,
    edited_at            BIGINT,
    deleted_at           BIGINT,
    created_at           BIGINT NOT NULL
);

-- The transcript query: everything in a conversation after a sequence.
CREATE UNIQUE INDEX IF NOT EXISTS messages_conversation_seq ON messages (conversation_id, seq);
CREATE INDEX        IF NOT EXISTS messages_sender           ON messages (sender_id);
CREATE INDEX        IF NOT EXISTS messages_expiry           ON messages (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX        IF NOT EXISTS messages_created          ON messages (created_at DESC);

-- Per-recipient sealed copy of a group message's content key.
CREATE TABLE IF NOT EXISTS message_keys (
    message_id  TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    wrapped_key TEXT NOT NULL,
    PRIMARY KEY (message_id, user_id)
);

CREATE TABLE IF NOT EXISTS attachments (
    id          TEXT PRIMARY KEY,
    message_id  TEXT   NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    kind        TEXT   NOT NULL,
    storage_key TEXT   NOT NULL,
    file_name   TEXT   NOT NULL DEFAULT '',
    mime_type   TEXT   NOT NULL DEFAULT 'application/octet-stream',
    size_bytes  BIGINT NOT NULL DEFAULT 0,
    width       INT    NOT NULL DEFAULT 0,
    height      INT    NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    blur_hash   TEXT,
    waveform    REAL[] NOT NULL DEFAULT '{}',
    -- The sealed AES key for the blob. Opaque to the server.
    media_key   TEXT,
    created_at  BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS attachments_message ON attachments (message_id);

CREATE TABLE IF NOT EXISTS reactions (
    message_id TEXT   NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id    TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji      TEXT   NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (message_id, user_id, emoji)
);

CREATE INDEX IF NOT EXISTS reactions_message ON reactions (message_id);

CREATE TABLE IF NOT EXISTS receipts (
    message_id TEXT   NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id    TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       TEXT   NOT NULL CHECK (type IN ('DELIVERED', 'READ')),
    at         BIGINT NOT NULL,
    PRIMARY KEY (message_id, user_id, type)
);

CREATE INDEX IF NOT EXISTS receipts_message ON receipts (message_id);

-- ---------------------------------------------------------------------------
-- Polls
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS polls (
    id                      TEXT PRIMARY KEY,
    message_id              TEXT    NOT NULL UNIQUE REFERENCES messages(id) ON DELETE CASCADE,
    question                TEXT    NOT NULL,
    allows_multiple_answers BOOLEAN NOT NULL DEFAULT FALSE,
    closes_at               BIGINT,
    is_closed               BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS poll_options (
    id       TEXT PRIMARY KEY,
    poll_id  TEXT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    text     TEXT NOT NULL,
    position INT  NOT NULL
);

CREATE INDEX IF NOT EXISTS poll_options_poll ON poll_options (poll_id, position);

CREATE TABLE IF NOT EXISTS poll_votes (
    option_id TEXT   NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    poll_id   TEXT   NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    user_id   TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    at        BIGINT NOT NULL,
    PRIMARY KEY (option_id, user_id)
);

CREATE INDEX IF NOT EXISTS poll_votes_poll ON poll_votes (poll_id);

-- ---------------------------------------------------------------------------
-- Status (stories)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS status_posts (
    id               TEXT PRIMARY KEY,
    author_id        TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind             TEXT   NOT NULL DEFAULT 'TEXT',
    text             TEXT   NOT NULL DEFAULT '',
    media_url        TEXT,
    background_color BIGINT,
    duration_ms      BIGINT NOT NULL DEFAULT 5000,
    audience         TEXT   NOT NULL DEFAULT 'CONTACTS',
    excluded_ids     TEXT[] NOT NULL DEFAULT '{}',
    created_at       BIGINT NOT NULL,
    expires_at       BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS status_author ON status_posts (author_id, created_at DESC);
CREATE INDEX IF NOT EXISTS status_expiry ON status_posts (expires_at);

CREATE TABLE IF NOT EXISTS status_views (
    status_id TEXT   NOT NULL REFERENCES status_posts(id) ON DELETE CASCADE,
    user_id   TEXT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    at        BIGINT NOT NULL,
    PRIMARY KEY (status_id, user_id)
);

-- ---------------------------------------------------------------------------
-- Calls
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS calls (
    id               TEXT PRIMARY KEY,
    conversation_id  TEXT   NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    initiator_id     TEXT   REFERENCES users(id) ON DELETE SET NULL,
    is_video         BOOLEAN NOT NULL DEFAULT FALSE,
    is_group         BOOLEAN NOT NULL DEFAULT FALSE,
    outcome          TEXT   NOT NULL DEFAULT 'ONGOING',
    started_at       BIGINT NOT NULL,
    ended_at         BIGINT,
    duration_seconds BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS calls_conversation ON calls (conversation_id, started_at DESC);

CREATE TABLE IF NOT EXISTS call_participants (
    call_id  TEXT NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
    user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at BIGINT,
    left_at   BIGINT,
    PRIMARY KEY (call_id, user_id)
);

CREATE INDEX IF NOT EXISTS call_participants_user ON call_participants (user_id);

-- ---------------------------------------------------------------------------
-- Housekeeping
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS schema_migrations (
    version    TEXT PRIMARY KEY,
    applied_at BIGINT NOT NULL
);
