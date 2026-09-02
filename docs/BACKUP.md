# Backup

How Ping's backups work, what they contain, and how to add a cloud destination.

There is **no cloud backup in this build**, and the backup screen says so rather than offering a
switch that would do nothing. What is here is a real, encrypted, restorable archive written to
the device — plus the seam a provider plugs into.

---

## The archive

A `.pingbak` file is an encrypted envelope around a zip.

```
PINGBAK1  version  kdf  iterations  saltLen  salt   ← plaintext header
uint16 length + Tink encrypted keyset                ← the data key, wrapped
Tink StreamingAead ciphertext …                      ← the zip
```

The zip inside contains:

```
manifest.json         version, timestamp, app version, counts, whether media is included
users.jsonl           one record per line
conversations.jsonl
messages.jsonl
attachments.jsonl
media/<filename>      the blobs, when media is included
```

**JSON Lines rather than one JSON array** so that export and import both stream. A history of
200,000 messages is written and read a page at a time and is never assembled in memory.

The records are the archive's **own types**, not the Room entities. Once a user has a `.pingbak`
on disk its shape is a compatibility promise; serialising entities directly would rewrite that
promise every time a column was added or renamed, and a rename would make old archives
unreadable with no warning. `manifest.version` is bumped only when a reader written for an older
version could misread the file, and a newer version is rejected with a message rather than
half-read.

Deliberately excluded: presence, unread counters, transfer progress, outbox state, and anything
else that is a cache of the server or of a moment in time. Restoring a stale "online" indicator
would be worse than not restoring one.

---

## Encryption

Envelope encryption, with every primitive coming from Google Tink or the platform's JCA:

1. A **key-encryption key** is derived — PBKDF2-HMAC-SHA256 at 600,000 iterations (OWASP's 2023
   guidance) over the passphrase, with a random 16-byte salt.
2. A fresh **Tink `StreamingAead` keyset** (AES-256-GCM-HKDF, 1 MiB segments) is generated as the
   data key and encrypts the zip as a stream. Streaming is what keeps a multi-gigabyte backup
   from having to fit in memory, and every segment is authenticated individually.
3. That keyset is wrapped under the KEK using Tink's encrypted-keyset format and stored in the
   header.

The plaintext header is bound into both operations as **associated data**. Editing the iteration
count or the salt in a hex editor makes the file fail to open rather than open more cheaply.

The streaming construction is Tink's, not this codebase's, precisely because hand-rolling a
chunked AEAD — deciding how segments are framed, how the last one is marked, how the nonce is
derived per segment — is where file-encryption code usually goes wrong.

### Two kinds of key

| | Passphrase | Device key |
| --- | --- | --- |
| Made by | "Back up with a passphrase" | Automatic background backups |
| Restorable on | Any device, with the passphrase | This install only |
| Key lives | Only in the user's head | Keystore-backed encrypted storage |
| Lost when | The passphrase is forgotten | The app is uninstalled, or the user signs out |

Automatic backups cannot use a passphrase: nothing can prompt for one from a background worker
at 4am, and storing one to use there would defeat the point of having it. So they use a random
256-bit device secret instead — already full entropy, so it is used directly as the KEK with no
stretching.

The consequence is stated in the backup screen, not left to be discovered at restore time: a
device-keyed archive **does not survive uninstalling the app**. Anyone who wants a backup they
can move elsewhere makes a passphrase one.

There is no passphrase recovery. Nobody, including whoever runs the server, can open a
passphrase-sealed archive without it.

---

## Restoring

1. The archive's manifest is read first, which is cheap because it is the zip's first entry.
   This is also how a wrong passphrase is caught — before anything is written to the database.
2. The confirmation shows real numbers from that manifest: how many messages, how many media
   files, when it was made.
3. Rows are then merged in, keyed by their client-generated ids, through the DAO path that
   maintains the full-text index — so restored history is searchable immediately.

Two honest limits:

- **A backup captures what this device held.** It is not a server-side archive, and cannot
  restore a conversation this device never received.
- **A restore merges, it does not replace.** Restoring twice is a no-op rather than a
  duplication, but a message deleted after the backup was taken will come back.

Restored messages land in a terminal status (`SENT` or `READ`). Restoring them as `PENDING`
would put old messages back in the send queue and re-send them, which is the kind of bug that
only shows up in somebody else's chat history.

Media entry names are reduced to their base name before being written, so an archive crafted
with `../` in an entry name cannot escape the media directory.

---

## Adding a cloud destination

`BackupDestination` is the whole interface:

```kotlin
interface BackupDestination {
    val id: String
    val displayName: String

    /** False when the provider needs configuration this build does not have. */
    suspend fun isConfigured(): Boolean

    suspend fun write(archive: File, onProgress: (Float) -> Unit = {}): Result<BackupHandle>
    suspend fun list(): Result<List<BackupHandle>>
    suspend fun read(handle: BackupHandle, into: File, onProgress: (Float) -> Unit = {}): Result<File>
    suspend fun delete(handle: BackupHandle): Result<Unit>
}
```

To add Drive, S3, or WebDAV:

1. Implement the interface. It receives an **already-encrypted file** — a destination never sees
   plaintext and never needs a key.
2. Change one binding in `di/RepositoryModule.kt`:
   ```kotlin
   @Binds abstract fun bindBackupDestination(impl: YourDestination): BackupDestination
   ```
3. Return `false` from `isConfigured()` when the credentials for it are absent. The backup
   screen already renders an unconfigured destination as an explanation rather than a broken
   button.

Nothing else in the app changes. `BackupEngine` produces a file; the destination moves it.

Credentials for a provider belong in environment variables or Gradle properties read into
`BuildConfig`, never in the repository — the same rule the rest of the project follows.
