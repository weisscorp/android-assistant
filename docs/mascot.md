# Android mascot

The home screen and the system voice-assistant window use the same native
`AssistantCoreView`. The character is an original soft, asymmetric goo creature
with oversized ivory eyes, a small mouth and a bright multicolor jelly body. Its contour
wobbles, squashes and stretches while its eyes blink and follow conversation states.
Its animations react to the existing conversation phases, without starting
audio, requesting permissions, sending data or calling a model.

| Conversation state | Visible behavior |
|---|---|
| Ready / idle | Relaxed open eyes, subtle breathing and occasional blink |
| Starting / routing / loading | Curious upward glance; three softly pulsing motes |
| Listening | Raised, stretched pose; microphone RMS gently stretches the body |
| Thinking / contact lookup | Tilted body and upward sideways glance; progress motes |
| Speaking | Synthesized PCM level opens the mouth and gently deforms the body |
| Tool / server work | Small elastic hops, scanning eyes and progress motes |
| Error / silenced microphone | Slumped body, worried eyebrows and a frown; existing explanatory text remains |

## Touch and appearance

The character has its own touch target. Pressing immediately squashes it and
changes its expression; tapping cycles through a springy jump, wink and wobble.
Dragging stretches and tilts it toward the finger, while its pupils track the
pointer. Releasing springs back; cancellation releases the gesture without
triggering a tap. Empty space outside the character remains available for
scrolling. The home screen, voice-assistant window and appearance settings also
observe touches across their entire window, including buttons, chat and sliders.
Screen coordinates are converted to the character's current position, so pupils
follow the finger even outside its view, after scrolling or in landscape. This
observer never consumes gestures or triggers character clicks. A separate gaze
tween returns the eyes gently on release/cancel, hands tracking to a remaining
finger for multi-touch, and clears when hidden or detached. Tracking overrides
idle/conversation glances while the finger is down; it does not use an overlay or
observe other apps or the system keyboard. The voice-session root handles unused
background touches to retain the event stream, after offering events to children.
These reactions use a separate tween from conversation poses, so
playing does not alter the current voice state. Keyboard and accessibility
clicks trigger the same reactions. No sound, microphone or model is started.
Blinking and winking animate the eye aperture and transition to a curved eyelid
with constant stroke thickness. The closed eye has no white sliver or pinched
pupil; rendering tests check closure, the unchanged other eye and exact reopening.

Settings → **Маскот и цвета** contains an interactive live preview, six vivid
palettes (Aurora, Candy, Sunset, Ocean, Lime, Cosmos), and separate custom hue
sliders for the top, bottom and highlights/blush. Selection is saved immediately
in private `mascot_appearance` preferences and shared by the home screen,
preview and assistant window. The chosen body colors persist across conversation
states; expressions, status text and processing motes still indicate the state.
Returning to custom colors restores the last custom hue values.

Text-only conversations never show a listening microphone or animate speech.
The adjacent status text and normal start/finish/stop controls remain accessible.
The mascot draws no transcripts, names, coordinates or replies on the lock screen.

Only this small view animates, at up to 30 fps while active, touched or reacting
and 15 fps while idle. Occasional idle glances supplement breathing and blinks.
Callbacks stop when hidden or detached. Geometry, paths, paints and gradients are
reused; the same mutable paths form the deforming outline and face. No WebView,
bitmap sprites, external animation runtime or new dependency
is needed. When Android's animator duration scale is disabled, poses settle into
static expressions and all animation callbacks stop. A tap with motion disabled
shows a static smile/wink, then resets once without a continuous frame loop.
Hiding or detaching the view cancels pending touch and feedback callbacks.
Re-enabling the setting
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
Disposable-emulator instrumentation covers real touch dispatch, press squash,
drag/return, gesture cancellation, static feedback with reduced motion, palette
preview and custom slider persistence across activity recreation. It also checks
rendered pupil movement from touches outside the character, multi-touch handoff,
cancel/return, and native button/palette dispatch in the home screen, voice panel
and appearance page. These interactions do not start a voice conversation or record audio.

Coucou's Mochi character, appearance, character animations, names, icons, sounds
and demo media are reserved by the author. They are not part of this Android
source release. Written author permission would be needed to distribute an
Android build with the actual Mochi character. The generic MIT mechanics do not
grant permission to copy its artwork or character choreography. This version
therefore contains original goo geometry and original state poses instead.

The soft-body direction was inspired by the playful creatures in **World of Goo**.
This implementation uses no game images, extracted assets, sounds or animations;
its silhouette, palette, face and motion are drawn in Kotlin for this project.

The model catalog, voice pipeline, tools and Hub protocol are independent of the
renderer. A mascot does not add remote tool execution or alter shared memory.
