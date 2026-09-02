# Security

What Ping protects, what it does not, and how. Written to be useful to somebody deciding
whether to trust it, which means being specific about the gaps.

---

## Threat model

**Protected against**

| Adversary | How |
| --- | --- |
| Someone reading the server's database | Message bodies are ciphertext the server has no key for |
| Someone tapping the network | TLS, plus a second layer of end-to-end encryption inside it |
| Someone who steals a database backup | Same as above; archives are separately encrypted |
| Another user probing for your details | Privacy audiences enforced server-side; phone numbers never returned to another user |
| Someone guessing passwords offline | scrypt at OWASP parameters, per-user salt |
| Someone replaying a stolen refresh token | Tokens are stored hashed and rotate on every use; reuse of a spent token is a 401 |

**Not protected against**

| Adversary | Why |
| --- | --- |
| Someone with your unlocked phone | App lock is a UI gate, not a second encryption layer. The message database is protected by Android's disk encryption and nothing more. |
| A compromised device, retroactively | No forward secrecy yet — see below |
| A malicious or compelled server operator substituting keys | Mitigated, not prevented: safety numbers can be compared out of band, and a peer's key changing raises a security notice |
| Traffic analysis | The server sees who talks to whom and when. Hiding that needs sealed sender or mixing, neither of which is implemented. |
| A hostile Android build or rooted device | Out of scope for any app |

---

## Message encryption

Google Tink, used as a library and never reimplemented:

- **One-to-one:** HPKE — X25519 key agreement, HKDF-SHA256, AES-256-GCM. The body is sealed to
  the recipient's published public key.
- **Groups:** the standard sender-key construction. The body is encrypted once under a fresh
  AES-256-GCM content key, and that key is sealed individually to each member's public key. A
  200-member group encrypts the body once, not 200 times.
- **Media:** each blob gets a fresh AES-256-GCM key, encrypted before upload. The key travels
  in the message envelope, so the media host stores bytes it cannot read.

The exact HPKE suite is pinned in code rather than negotiated:

```kotlin
HpkeParameters.builder()
    .setVariant(HpkeParameters.Variant.TINK)
    .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
    .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
    .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
    .build()
```

No primitive, mode, or construction is invented in this codebase. The only thing the crypto
layer does itself is *sequence* Tink calls, following the shape Tink's own hybrid-encryption
guide prescribes.

### The honest limitation

This is sealed-sender-style public-key encryption, **not a Double Ratchet**. Concretely:

- **No forward secrecy.** An attacker who obtains a device's private key can decrypt that
  device's past ciphertexts, if they also captured them.
- **No post-compromise security.** Compromise is not self-healing; there is no ratchet to walk
  the keys forward.

Signal's X3DH plus Double Ratchet is the right answer, and `CryptoService` is shaped as an
interface with exactly that replacement in mind — a libsignal-backed implementation can be
swapped in without touching a single caller. Until that exists, this document says so rather
than letting the word "end-to-end encrypted" carry an implication it has not earned.

### Key storage

Private keys live in `EncryptedSharedPreferences` with an AES-256-GCM master key in the Android
Keystore. On a device with a secure element — most phones since 2018 — the master key is
hardware-bound and cannot be extracted even from a rooted device.

If the encrypted store is unreadable (it happens, usually after a botched device-to-device
restore) the store rebuilds itself once. The user is signed out, which is the correct outcome:
unreadable credentials are not credentials. If the Keystore is unavailable entirely, the app
runs with an in-memory store and the user signs in each launch — a silent downgrade to
plaintext storage would be worse.

---

## Passwords

scrypt from the Node standard library, at N=2^17, r=8, p=1 — OWASP's recommended parameters —
with a per-user salt. Hashes are stored in a self-describing format:

```
scrypt$N$r$p$salt$hash
```

so parameters can be raised later without a flag day: an old hash still says how to verify
itself, and can be upgraded on next login.

Two details that matter more than the algorithm:

- **Login is constant-time with respect to account existence.** An unknown address is verified
  against a dummy hash, so it costs the same as a wrong password. Otherwise response timing
  enumerates accounts.
- **Password reset answers identically for known and unknown addresses.** Same reason.

Password rules are length-based (NIST SP 800-63B), not character-class based. Composition rules
push people towards `Password1!` and buy nothing.

---

## Tokens

- Access tokens: JWT, HS256, 15 minutes by default.
- Refresh tokens: opaque, **stored hashed**, and rotated on every use. Presenting a token that
  has already been spent is a 401 and invalidates the chain — which is what turns a stolen
  refresh token from permanent access into a detectable event.
- The two signing secrets must be different, and boot fails if they are not, so an access token
  can never be replayed as a refresh token.

Concurrent 401s are serialised through a mutex in `AuthInterceptor`, which re-reads the token
after taking the lock. Ten parallel requests hitting an expired token produce one refresh, not
ten.

---

## Privacy controls

Seven audiences — last seen, online, profile photo, about, status, groups, calls — each
`EVERYONE`, `CONTACTS`, or `NOBODY`, and each **enforced on the server**. The client hiding a
field it was sent is not privacy.

`projectUser()` returns a fixed shape with hidden fields nulled rather than omitted, because a
different response *shape* leaks the setting itself. Phone numbers are never returned to
another user regardless of settings: contact discovery works on salted hashes of numbers, and
the app's own identity is a username.

Blocks are enforced in both directions, on both sides, on the server.

Read receipts are reciprocal, enforced in the view-model rather than described in the UI copy:
turning yours off also stops you seeing other people's.

---

## The app on the device

- `allowBackup="false"` and data-extraction rules that exclude everything. The message database
  and the key material must not leave the device through a channel the user did not choose. The
  in-app backup, which the user does choose, is separately encrypted — see [BACKUP.md](BACKUP.md).
- `FLAG_SECURE` applied from the screenshot-blocking setting, which also removes the app from
  the recents thumbnail.
- Cleartext HTTP is refused outside loopback, so a mistyped server address fails rather than
  silently sending traffic in the clear.
- Media is picked through Android's photo picker, which needs **no storage permission at all**
  and gives per-item rather than all-or-nothing access to the photo library.
- The camera is used as a sensor for QR scanning: frames are analysed and discarded, never
  written or uploaded.

---

## Reporting a vulnerability

Open a GitHub issue for anything already public. For anything not public, use GitHub's private
vulnerability reporting on the repository rather than an issue.
