# Calls

Ping's calls are real WebRTC, not a placeholder. This explains the topology, what STUN and TURN
are actually for, and why a deployment that skips TURN will work for most people and fail for
some.

---

## What happens when you place a call

1. `POST /v1/calls` tells the server who is being called. The server checks the callee's `calls`
   privacy audience and whether either party has blocked the other, then notifies them over the
   WebSocket.
2. Both sides build a `PeerConnection` with the ICE servers the backend supplies at
   `GET /v1/calls/config`.
3. The caller creates an offer; the callee answers. Both exchange ICE candidates as they are
   discovered. All of this goes through Ping's own WebSocket — the server relays SDP and ICE
   without inspecting them.
4. Once a candidate pair succeeds, **audio and video flow directly between the two devices**.
   They do not pass through the Ping server at any point.

The configuration is pinned rather than left to defaults:

| Setting | Value | Why |
| --- | --- | --- |
| SDP semantics | `UNIFIED_PLAN` | The current standard; Plan B is deprecated and interoperates badly |
| Bundle policy | `MAXBUNDLE` | One transport for audio and video, so one successful candidate pair is enough |
| RTCP mux | `REQUIRE` | Halves the number of ports to negotiate |
| Gathering | `GATHER_CONTINUALLY` | Keeps finding candidates after the initial round, which is what lets a call survive Wi-Fi-to-mobile handover |
| Capture | 1280×720 at 30 fps | A ceiling, not a target: WebRTC's own bandwidth estimation scales down from here |

Two details that are easy to get wrong and are handled explicitly:

- **ICE candidates are buffered until the remote description arrives.** Candidates routinely
  turn up before the answer does; adding one to a peer connection with no remote description
  throws, and the call fails for no visible reason.
- **Teardown is ordered.** Capture is stopped, then the video source is disposed, then the peer
  connection. Getting that order wrong is what leaves the camera light on after a call ends.

---

## STUN, TURN, and the 10–20%

A phone on a home or mobile network has a private address. To receive media it needs to know
what address the other side should send to.

**STUN** is a server that answers one question: "what address and port do you see me coming
from?" That is enough for most networks. It costs almost nothing to run, and public STUN
servers exist — the default configuration points at one.

**TURN** is a relay. When both parties are behind *symmetric* NAT — which reassigns the port
per destination, so the address STUN reported is useless to a third party — no direct path can
be found at all. The only remaining option is for both sides to send media to a server that
forwards it. That server carries the full bitrate of every call it relays, which is why nobody
runs a free public one.

**Roughly 10–20% of call attempts need TURN**, depending on the mix of carrier and corporate
networks involved. Without it, those calls ring and then fail to connect.

Ping does not ship a TURN server, and the app says so rather than ringing forever: with no ICE
configuration at all, the calls screen shows an explicit notice instead of a call button that
would fail silently.

### Configuring it

Backend, which serves this to clients at `/v1/calls/config`:

```bash
STUN_SERVERS=stun:stun.example.org:3478
TURN_URL=turn:turn.example.org:3478
TURN_USERNAME=…
TURN_CREDENTIAL=…
```

Baked into an APK built by CI, as a repository variable:

```
PING_STUN_SERVERS=stun:stun.example.org:3478
```

Or set at runtime in **Settings ▸ Advanced ▸ STUN/TURN servers**, which is the practical option
for an APK downloaded from an Actions run.

[coturn](https://github.com/coturn/coturn) is the usual choice, and use
**ephemeral credentials** (a time-limited HMAC of a shared secret) rather than a static username
and password — a static TURN credential in a client APK is a free relay for anyone who extracts
it.

---

## Group calls are a full mesh

Every participant sends its stream to every other participant. With *n* people, each device
uploads *n−1* streams.

- 2 people: 1 upload each. Fine.
- 4 people: 3 uploads each. Usually fine on Wi-Fi.
- 8 people: 7 uploads each — roughly 7 Mbps up for video. Most home connections cannot do it,
  and most phones will thermally throttle trying.

A real deployment wants an **SFU** (selective forwarding unit — LiveKit, Janus, mediasoup):
every device uploads one stream, the SFU forwards what each participant needs. That is a server
component with its own scaling story, which is why it is not in this repository. Mesh is honest
for small calls and is labelled as such rather than presented as a general group-calling
feature.

---

## Notifications and the lock screen

Incoming calls use `NotificationCompat.CallStyle` on API 31 and above, which is what makes a
call render as a call — full-width answer and decline buttons, the right priority, and correct
behaviour on the lock screen. Below 31 it falls back to a full-screen intent with the same two
actions.

The full-screen intent needs `USE_FULL_SCREEN_INTENT`, and on API 34+ the system only honours it
for calls and alarms — which is exactly what this is.

While a call is connected, a foreground service with the `microphone` and `camera` types keeps
it alive, and the ongoing notification is the one the system requires for that. Removing it is
not an option, and it should not be: a call that keeps the microphone open without saying so is
the behaviour of malware.

---

## What is deliberately not implemented

- **Call recording.** Trivially easy and a privacy disaster in a messenger.
- **An SFU.** See above.
- **Screen sharing.** Needs `MediaProjection` plus its own consent flow; the WebRTC side would
  be simple, the UX around it is not.
