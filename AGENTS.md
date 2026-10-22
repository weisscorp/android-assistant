# Contributor instructions

Use synthetic fixtures and temporary state for tests. Never commit personal
knowledge, conversation history, credentials, runtime configuration or captures.
Do not connect to physical devices, install applications, record audio or run
sound checks without a fresh explicit request from their owner. Device tests
must target an explicitly selected disposable emulator. Enable delivery automations
only after an explicit owner request. Preserve third-party authorship and license notices.

## Offline voice control

For on-device controls and spoken clarification read `docs/offline-control.md`.
Direct commands use PhoneCommands/PhoneControl and do not require an LLM/server.
Preserve literal dictation, explicit confirmations and fresh screen snapshots.
LOCAL is the default conversation mode, not a network firewall. Do not promise
fresh internet content offline or unrestricted control of protected Android UI.
Validate on an explicitly selected silent emulator; restore settings changed by tests.

## Speech recognition

See `docs/speech-engines.md` for Vosk, T-one and GigaAM v3 selection, pinned weights,
PCM segmentation and tests. Keep `SpeechEngines` as the common selection source
for voice capture, system RecognitionService, notes and diagnostics. Preserve the
legacy `tone_asr` setting; do not use Vosk words as GigaAM partial/final text.

For timer/alarm audio, full-screen signals and bounded voice stop, read
`docs/clock-signals.md`. Test ringing only on an explicitly selected silent emulator.
Keep reminders as notifications and release the microphone on every ring exit.

For app opening from keyguard see `docs/locked-app-launch.md`. Use Android's
credential UI, preserve one-shot expiry/cancellation and revalidate the package.
Never test synthetic PIN setup on a physical phone or overwrite an unknown PIN.

For spoken numbers, dates and clock times see `docs/voice-response.md`.
`SpokenCalendar` runs before `SpokenDecimals` in the shared TTS payload for both
voices. Keep display text intact; distinguish dotted clock times from fractions
by context and preserve URLs, versions and identifiers. Test synthetic synthesis
without playback on an explicitly selected emulator.
