# Ping

A private messenger for Android, with the backend it needs.

Ping is a full messaging application — one-to-one and group chats, media, voice and video
calls, status updates, end-to-end encryption — built to work offline first and to be honest
about what it does and does not do.

**You do not need Android Studio, Gradle, a JDK, or the Android SDK to get an APK.** Push to
this repository and GitHub Actions builds one; download it from the run's artifacts.

---

## Getting the APK

1. Open the **[Actions](../../actions)** tab.
2. Pick the most recent **Android CI** run on your branch.
3. Scroll to **Artifacts** at the bottom of the run summary.
4. Download **`ping-debug-apk`** (or `ping-release-apk`) and unzip it.
5. Copy the `.apk` to an Android 8.0+ device and open it. You will need to allow installation
   from your file manager the first time.

The run summary also prints a table of the APKs it produced and their sizes.

| Artifact | What it is |
| --- | --- |
| `ping-debug-apk` | Debug build. Larger, debuggable, installs alongside a release build. |
| `ping-release-apk` | R8-shrunk and resource-optimised. Signed with the debug key unless you configure a release keystore. |
| `unit-test-report` | HTML test report. |
| `lint-report` | Android Lint findings, HTML and SARIF. |

Tagging a commit `v1.2.0` additionally runs the **Release** workflow, which builds a signed APK
and AAB, generates `SHA256SUMS.txt`, and publishes a GitHub Release.

---

## What is here

```
├── android/                  Android app (Kotlin, Jetpack Compose, Material 3)
│   ├── app/src/main/java/com/ping/messenger/
│   │   ├── core/             Cross-cutting: crypto, network, media, notifications, work
│   │   ├── data/             Room database, Retrofit APIs, WebSocket, repositories
│   │   ├── domain/           Models and repository interfaces
│   │   ├── feature/          One package per screen area
│   │   └── ui/               Design system, components, navigation
│   ├── app/src/test/         Unit tests
│   └── gradle/libs.versions.toml   Every dependency version, pinned
├── backend/                  API, realtime gateway and WebRTC signalling (TypeScript)
│   ├── src/routes/           REST endpoints
│   ├── src/realtime/         WebSocket gateway and fan-out
│   ├── src/db/migrations/    SQL schema
│   └── test/                 Unit and end-to-end tests
├── docs/                     Architecture, security, backend, calls, backup
└── .github/workflows/        android.yml, release.yml, backend.yml
```

---

## Features

### Messaging
Text, emoji, images, video, GIFs, audio files, voice messages with a real waveform, documents,
contact cards and location. Replies, forwarding, reactions, editing with history, delete for me
or for everyone, starring, copy, and per-message delivery information.

Message states are `Sending → Sent → Delivered → Read`, with `Failed` and a retry. A receipt
that arrives out of order can never move a message backwards through that sequence.

### Groups
Name, icon, description, member list, owner/admin/member roles, add and remove, invite links
that can be reset, and a permission matrix for who may post, edit group info and add members —
all enforced server-side, not just hidden in the UI. Mentions, pinned messages, per-group
notification settings and a per-group media gallery.

### Calls
Real WebRTC. One-to-one voice and video with camera switching, mute, and speaker toggle;
`CallStyle` notifications that ring over the lock screen; and a call history with direction and
outcome. Media flows directly between devices and never through the server.

### Status
Text, photo and video updates that expire after 24 hours, with a full-screen viewer, tap
navigation, hold-to-pause, per-post duration, audience control and view counts.

### Offline
Every action writes to the local database and appends to an outbox; nothing in the UI waits on
the network. Drafts, queued messages, cached media and a full local message history all work
with no connection, and the outbox drains when one returns.

### Privacy and security
End-to-end encryption, per-field privacy audiences (last seen, online, photo, about, status,
groups, calls), reciprocal read receipts, blocking enforced in both directions, reporting,
two-step verification, linked-device management, app lock and screenshot blocking.

### Beyond WhatsApp
Usernames instead of phone numbers, QR contact adding, chat folders, message scheduling,
polls, conversation notes, full-text local search, per-chat wallpapers, disappearing messages,
reminders, six notification channels, an in-app text-size control, reduced-motion and
high-contrast modes.

---

## Architecture

**Android** — MVVM with a repository layer, single-activity Compose navigation.

```
Compose screen  →  ViewModel (StateFlow)  →  Repository  →  ┬→ Room (source of truth)
                                                            └→ Outbox → SyncWorker → API
```

The important property is that **the UI never awaits the network**. A repository writes to Room
and appends an outbox row in one transaction; the screen is already updated because it observes
Room. `SyncWorker` drains the outbox whenever connectivity allows. That is what makes the app
work offline without a single "are we connected?" branch in a view-model.

**Backend** — Fastify 5 over Postgres. Stateless JWT access tokens with rotating refresh
tokens; a WebSocket gateway for realtime events and WebRTC signalling; media uploaded straight
to storage through signed tickets so blobs never pass through the API process.

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for the full picture, including why the
Android app is a single Gradle module and where the seams are if you want to split it.

---

## Running the backend

The app ships pointing at a placeholder host, so you will want your own backend to actually
send a message.

```bash
cd backend
cp .env.example .env

# Generate the two JWT secrets (they must be different)
echo "JWT_ACCESS_SECRET=$(openssl rand -base64 48)"  >> .env
echo "JWT_REFRESH_SECRET=$(openssl rand -base64 48)" >> .env

# Point DATABASE_URL at a Postgres 14+ instance, then
npm install
npm run migrate
npm run dev
```

With `EMAIL_DRIVER=console` (the default) verification codes are **printed to the server log**
instead of emailed, so you can register an account with no email provider configured.

Point the app at it by building with the URL baked in:

```bash
cd android
./gradlew :app:assembleDebug -PpingApiBaseUrl=http://10.0.2.2:8080/
```

`10.0.2.2` is the host machine as seen from the Android emulator. On a physical device use your
machine's LAN address. The address can also be changed at runtime in **Settings ▸ Advanced**.

See **[docs/BACKEND.md](docs/BACKEND.md)** for every environment variable, the storage and
email drivers, and what a production deployment needs that this repository does not ship.

---

## Configuration

Nothing secret is committed. Everything is supplied through environment variables, Gradle
properties, or GitHub repository settings.

### GitHub repository variables (Settings ▸ Secrets and variables ▸ Actions ▸ Variables)

Both are optional; the build works without them.

| Variable | Purpose |
| --- | --- |
| `PING_API_BASE_URL` | Baked into the APK as the default server address. |
| `PING_STUN_SERVERS` | Comma-separated ICE servers, so calls work in the built APK. |

### GitHub secrets — only for signed releases

| Secret | Purpose |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Without them, the release APK is signed with the debug identity. That installs and runs fine
for testing, but cannot be uploaded to the Play Store or used to update an existing install.

### Signing releases

```bash
keytool -genkeypair -v -keystore release.jks -alias ping \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks    # paste into RELEASE_KEYSTORE_BASE64
```

For local release builds, create `android/keystore.properties` (git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=ping
keyPassword=…
```

---

## Development

Local tooling is optional — CI builds everything. If you do want to build locally you need
JDK 17 and the Android SDK (platform 35, build-tools 35.0.0). The Gradle wrapper is committed,
so there is no Gradle install step.

```bash
cd android
./gradlew :app:compileDebugKotlin    # fastest check
./gradlew :app:testDebugUnitTest     # unit tests
./gradlew :app:lintDebug             # Android Lint
./gradlew :app:assembleDebug         # APK at app/build/outputs/apk/debug/
```

```bash
cd backend
npm run typecheck
npm test                             # unit tests; end-to-end tests need DATABASE_URL
npm run build
```

The backend's end-to-end tests run the real Fastify app against a real Postgres with no mocks.
They skip automatically when `DATABASE_URL` is unset, so `npm test` works without a database.

---

## Security

Ping encrypts message bodies end to end using **Google Tink** — HPKE with X25519 key agreement,
HKDF-SHA256 and AES-256-GCM. No cryptographic primitive or construction is invented here; the
code sequences audited library calls.

**What that does and does not give you** is stated plainly in
**[docs/SECURITY.md](docs/SECURITY.md)**. The short version: message bodies are unreadable to
the server, but this is sealed-sender-style encryption rather than a full Double Ratchet, so
there is **no forward secrecy or post-compromise security** yet. The `CryptoService` interface
is shaped so a libsignal-backed implementation can replace it without touching a caller.

Other measures: passwords hashed with scrypt at OWASP-recommended parameters; refresh tokens
stored hashed and rotated on every use; private keys in Keystore-backed encrypted storage;
cleartext HTTP refused outside loopback; automatic cloud backup switched off so the message
database cannot leave the device through a channel the user did not choose.

---

## Honest limitations

Things this repository does **not** do, stated here rather than discovered later:

- **No cloud chat backup.** Backups are real, encrypted, restorable archives — but they are
  written to the device. A passphrase-sealed archive can be copied off the phone and restored
  anywhere; an automatic one is sealed with a device key and does not survive uninstalling the
  app, which the backup screen says rather than leaving it to be discovered at restore time.
  `BackupDestination` is the seam for adding a provider — see [docs/BACKUP.md](docs/BACKUP.md).
- **No TURN server.** Calls work on most networks with STUN alone; roughly 10–20% need a TURN
  relay, which has to be configured. The app says so instead of ringing forever.
- **No push provider.** Messages arrive over the app's own WebSocket while it is running, and
  missed messages are fetched on next launch. Adding FCM needs a `google-services.json`, which
  cannot be committed.
- **No SMTP transport.** Verification codes print to the server log in development. Wiring a
  real provider is a deployment choice.
- **No translation provider.** The plumbing is real; shipping message text to a third-party API
  by default would contradict the rest of the app.
- **Single backend instance.** The realtime fan-out is in-memory, so two API replicas would
  each only reach their own clients. [docs/BACKEND.md](docs/BACKEND.md) describes the pub/sub
  change needed to scale out.
- **Group calls are a full mesh**, which is fine up to about four people and then limited by
  upstream bandwidth. A real deployment wants an SFU.

---

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — layers, data flow, and the decisions behind them
- [docs/SECURITY.md](docs/SECURITY.md) — threat model, what encryption does and does not cover
- [docs/BACKEND.md](docs/BACKEND.md) — API surface, configuration, deployment
- [docs/CALLS.md](docs/CALLS.md) — WebRTC topology and what STUN/TURN are for
- [docs/BACKUP.md](docs/BACKUP.md) — archive format and how to add a cloud destination

---

## Licence

Apache License 2.0 — see [LICENSE](LICENSE).
