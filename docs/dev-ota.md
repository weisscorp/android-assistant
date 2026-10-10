# Development OTA

Settings → Server → **Dev OTA** configures a separate HTTPS endpoint, bearer key,
optional LAN certificate SHA-256 pin and automatic updates. It does not change
the conversation provider. Credentials are encrypted with a separate Android
Keystore key. Automatic updates start disabled, including after a fresh install.
Importing an owner-supplied JSON settings file can enable them explicitly:

```json
{
  "endpoint": "https://192.168.1.2:8779",
  "token": "REPLACE_WITH_YOUR_PRIVATE_DEV_OTA_KEY",
  "certificateSha256": "REPLACE_WITH_64_HEXADECIMAL_CHARACTERS",
  "automatic": true
}
```

Treat that file as a credential; delete the external copy after importing it.
A development administrator can provision the same JSON as `dev-ota-setup.json`
inside the app's private files directory. On the next ordinary process start it
is encrypted, consumed and deleted. There is no exported provisioning intent.
Never bundle keys or machine addresses in a distributed APK.

Grant **Install unknown apps** to the assistant once in Android settings. The
app requests silent **self-updates** through PackageInstaller and declares
`UPDATE_PACKAGES_WITHOUT_USER_ACTION`. This is supported under Android's
[self-update conditions](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int));
an OEM policy or a future Android release can still require confirmation. That
callback creates a quiet notification and leaves opening the installer to a tap.
It never launches a voice session, microphone, TTS or startup cue.

JobScheduler checks on an unmetered network, normally about every 15 minutes, and
after leaving an assistant screen. Doze/background restrictions may delay it.
An active conversation, assistant screen, recording, call or ringing alarm defers
installation. Downloaded files are streamed, bounded to 1 GiB, hash checked and
validated against the installed package, monotonic version and signing key.
Changing servers invalidates previously downloaded candidates. Disabling OTA
cancels scheduled checks/downloads; a session already committed to Android may
finish. Replacement and reboot restore scheduling without opening the assistant.

The server side is documented in
[assistant-hub development OTA](https://github.com/weisscorp/assistant-hub/blob/main/docs/dev-ota.md).
The channel is for trusted owner commits and an existing matching development
signing key. A release signed with a different key cannot replace an installed app.

For repository tests, use an explicitly selected disposable emulator. The
`AutoUpdateTest` suite includes synthetic configuration/scheduling tests and two
separately selected real self-update tests. `QuietUpdateTest` checks callback
silence and prevents automatic installer activity launches. Neither suite is a
command to run on a physical phone.
