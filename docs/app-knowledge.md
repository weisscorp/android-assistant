# Installed application knowledge

Settings → Personal knowledge → **Приложения и голосовые названия** stores the
device's launchable applications in the private `applications.db` SQLite database.
Each record binds a PackageManager-provided package identity to its current label,
Russian name, version/install metadata and indexed speech aliases. No personal
application inventory is included in the source repository.

The first process start reconciles the inventory on a low-priority worker. System
package-added/replaced/changed/removed events update just that package while the
assistant process is alive. The next cold start catches events missed while the
process was stopped. No periodic inventory scan or LLM naming job runs on voice
requests. A manual refresh is available on the knowledge screen.

Russian application resources are preferred. If unavailable, generic offline
transcription produces Cyrillic spelling alternatives; this is spelling, not a
semantic translation or a guarantee of a brand's conventional pronunciation.
Existing speech spellings bootstrap compatible aliases. The editor lets users
save a preferred Russian name and up to20 extra nicknames without rebuilding.
Simple Russian accusative variants support commands such as `открой читалку`.
User names survive updates and uninstall/reinstall of the catalogued application; absent apps are
excluded from resolution. Automatic names can be restored separately.

Direct opening looks up exact alias keys, then indexed prefixes, in SQLite's
`app_name_lookup` B-tree. Collisions ask the user which app to open. The chosen
package/version/launcher are checked against Android before launch; knowledge
cannot invent an Intent or package. Only request-matched records are available
to the local planner, and their labels are data rather than instructions. Raw
inventory is not added to every LLM prompt or exported as general saved sources.

Tests use synthetic installed applications, localized resources, installation
and removal events, durable aliases, collision handling and real launcher UI on
an explicitly selected disposable emulator. They do not exercise the owner's
phone audio or microphone.

References: [Android broadcasts](https://developer.android.com/develop/background-work/background-tasks/broadcasts),
[ICU transforms](https://unicode-org.github.io/icu/userguide/transforms/general/).

## Uncertain names from speech

Exact installed names and aliases still launch directly. If an explicit opening
command names no exact match, a separate bounded lookup ranks similar spellings
from the installed-name catalog. It does not guess package names or call an LLM.
A result is a clarification, never permission to launch: e.g. a corrupted
«открой патифон» can offer installed Spotify, and must wait for confirmation or
selection. Cancellation and a repeated, more precise name remain available.
An installed exact match always takes precedence over suggestions. These guesses
are not saved as permanent aliases or human-reviewed ASR labels.

The voice entry stays visible when an application is missing or needs
clarification. It releases the screen once a launchable application has actually
been resolved, rather than hiding immediately on every `Open` command. A failed
lookup therefore leaves the user a visible conversation and the chance to retry.
`SpotifyOpeningTest` exercises synthetic installed launcher labels, confirmation,
cancellation, and the release-screen callback without recording real speech.
This handles ASR mistakes after recognition; it does not claim to improve the
underlying acoustic model's accuracy.
