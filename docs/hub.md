# Connect to Assistant Hub

Start the [Hub repository](https://github.com/weisscorp/assistant-hub) independently.
Its `tools/local_stack.py` generates a private runtime, bearer token and local UI.
Both repositories can be cloned side by side; neither imports the other's source
at Android build time. They communicate over the documented conversation/link APIs.

The Hub currently binds `http://127.0.0.1:8778` only. Android rejects cleartext
server URLs. Deploy an authenticated HTTPS gateway before pairing a real phone:
proxy `/conversation` and `/link/...` to Hub, preserve Authorization, and supply
a permitted loopback upstream Host. Network deployment is outside this initial
source publication; no listener is exposed by cloning or building these repos.

Configure the gateway endpoint and private Hub token through app settings. Use
system-trusted TLS, or explicit LAN certificate pinning with an IP SAN and a
SHA-256 certificate fingerprint delivered through a trusted channel. Certificate
pinning does not disable hostname/expiry checks and does not do automatic trust.
No personal server address or credential is a shipped default.

Knowledge snapshots include user-saved sources/aliases, person bindings and notes.
Images have content hashes. The phone is authoritative for these records; the Hub
stores references for indexed discovery and can retrieve details with
`phone_knowledge`. Saved observations are timestamped and must not be presented
as fresh locations without checking their source.

The current conversation adapter forwards latest recognized/typed text to a
selected Hub provider and returns text for local synthesis. Although it validates
the request's phone history/capability fields, Hub does not yet pass all of those
through or return arbitrary device actions (`action` is null). Full shared task
context selection and bidirectional phone tool execution remain future work.

Inbox notifications use polling/Android background scheduling, which Doze can
delay; they are not instant push. Slack/mail monitoring and Internet exposure
are not installed by default. See the Hub's
[connection contract](https://github.com/weisscorp/assistant-hub/blob/main/docs/android.md)
for current scope and server-side code.
