# Android mascot

The home screen and the system voice-assistant window use the same native
`AssistantCoreView`. The character is an original ceramic robot with a polygonal
body, recessed visor, antenna and two separate microphone sensor pods.
Its animations react to the existing conversation phases, without starting
audio, requesting permissions, sending data or calling a model.

| Conversation state | Visible behavior |
|---|---|
| Ready / idle | Relaxed open eyes, subtle breathing and occasional blink |
| Starting / routing / loading | Antenna light pulses; three progress lights |
| Listening | Attentive raised pose; microphone RMS drives the sensor meters |
| Thinking / contact lookup | Tilted head and antenna; eyes look up; progress lights |
| Speaking | Synthesized PCM level drives the mouth display |
| Tool / server work | Scanning eyes and progress lights |
| Error / silenced microphone | Distinct eyes, tilted pose and visible warning badge; existing explanatory text remains |

Text-only conversations never show a listening microphone or animate speech.
The adjacent status text and normal start/finish/stop controls remain accessible.
The mascot draws no transcripts, names, coordinates or replies on the lock screen.

Only this small view animates, at up to 30 fps while active and 15 fps while idle.
Callbacks stop when hidden or detached. Geometry, paths, paints and gradients are
cached; no WebView, bitmap sprites, external animation runtime or new dependency
is needed. When Android's animator duration scale is disabled, poses settle into
static expressions and all animation callbacks stop. Re-enabling the setting
while the view is visible resumes animation.

## Upstream code and artwork boundaries

`MascotTween.kt` adapts the generic `Ease`, `TweenKey`, `Tween` and interpolation
mechanics of Louis Raillé's **Coucou v0.2.3**, commit
`5cb2a27af352cf7319569920aac899efd42b54bc`:

- [BotEngine.swift](https://github.com/Louis-CFM/coucou/blob/5cb2a27af352cf7319569920aac899efd42b54bc/NotchBuddy/Sources/CoucouKit/BotEngine.swift)
- [MIT source-code license](../app/src/main/assets/licenses/Coucou-LICENSE.txt)
- [Separate artwork license](https://github.com/Louis-CFM/coucou/blob/v0.2.3/LICENSE-ASSETS.md)

The Kotlin adapter uses monotonic frame deltas, carries elapsed time across
successive keyframes, and supports immediate settlement and interruption.
JVM tests cover long frames, interrupted tracks and disabled-motion settlement.

Coucou's Mochi character, appearance, character animations, names, icons, sounds
and demo media are reserved by the author. They are not part of this Android
source release. Written author permission would be needed to distribute an
Android build with the actual Mochi character. The generic MIT mechanics do not
grant permission to copy its artwork or character choreography. This version
therefore contains original robot geometry and original state poses instead.

The model catalog, voice pipeline, tools and Hub protocol are independent of the
renderer. A mascot does not add remote tool execution or alter shared memory.
