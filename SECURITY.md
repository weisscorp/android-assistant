# Local data and permissions

The assistant can process microphone audio, screen content, contacts, notifications
and user knowledge when corresponding permissions/features are enabled. Android
permission and lock-screen boundaries still apply; accessibility is not unrestricted
privileged access. Enable only the capabilities you intend to use.

The interaction journal is currently enabled by default and can hold verbatim
requests, model input/replies, tool calls/results and action data. It is bounded
to 50,000,000 bytes, keeps complete records and supports disabling/export/clear
through settings. Treat exports as personal data. No private journal, recording,
database, chat link or source recipe belongs in an issue or public repository.

Server credentials use the app's protected storage path. HTTPS and explicit LAN
certificate validation are required for server access. A trusted server/provider
may receive the text and knowledge you send to it. Use a private authenticated
endpoint; never paste live tokens into configuration examples or screenshots.

This source release does not distribute signing keys, weight archives or APKs.
Our MIT license does not change third-party voice/model/library terms. Building
or testing here must not automatically install, launch or record on physical
phones. Use an explicitly selected disposable emulator for device tests.
