# Headset voice activation

Explicit voice entry is `VoiceCommandActivity` (`ACTION_VOICE_COMMAND`,
`ACTION_ASSIST`, hands-free search and voice-assist actions). It starts one
conversation after `onPostResume`, when the entry activity is eligible to start
microphone foreground work. `onCreate` only creates the panel. A pause before
execution keeps the entry pending; an ordinary later resume does not invoke it
again. A new intent is another button invocation and stops an active conversation.

Do not gate this explicit, visible invocation on
`RoleManager.isRoleHeld(ROLE_ASSISTANT)`. That used to redirect to idle
`MainActivity` without recording when the role was absent, including after a
system upgrade or OEM assistant-selection changes. Being the selected system
assistant is still needed for Android to dispatch its system assistant affordance;
this change does not select the role or grant microphone/Bluetooth permissions.
The chooser's “Always” choice makes the app the preferred handler for that voice
intent; it does not by itself grant `ROLE_ASSISTANT`. This preferred-handler path
is a supported explicit voice invocation and must not require a second assistant
selection. Do not auto-change the system role to work around the bug.
The ordinary launcher icon continues to open idle home without starting capture.

Regression coverage: `AssistantVoiceEntryTest`, on a disposable Android 16/API 36
emulator without the assistant role and with granted microphone permission. It
checks explicit voice command -> listening, no home redirect, second invocation
-> stopped, and idle launcher entry. `HeadsetButtonTest` covers cancellation while
thinking and headset audio transport termination. Use an explicitly chosen emulator
serial; these tests must never run on a user's physical phone.

A silent emulator validates activity/foreground-service lifecycle and PCM capture,
not Bluetooth HFP transport or an OEM's hardware button dispatch. Physical headset
verification remains a separate user action.

## Choosing the system assistant versus screen search

Settings -> Access opens the public `ACTION_VOICE_INPUT_SETTINGS` page. On AOSP
Android 16 this is a two-step screen: tap "Default digital assistant app", then
select Assistant and accept Android's screen-access confirmation. Merely opening
the first page changes nothing. The app displays both role selection and whether
its actual VoiceInteractionService is selected. The gesture itself is not fired
by this status check.

Do not replace this with an unconditional `createRequestRoleIntent(ROLE_ASSISTANT)`:
Android 16 AOSP declares this role `requestable=false`; an available role need not
have an app-requestable grant dialog. Do not write privileged settings or silently
assign roles as a substitute for user selection.

If the system service is already selected but holding the navigation handle still
opens Google Circle to Search, this is a distinct navigation feature. Its switch
and gesture mapping belong to the ROM; assigning an assistant does not guarantee
that a Google-specific long-press can be reassigned. Ask which default app/service
is selected before attributing the behavior to the assistant's session code.

### LineageOS 23 (Android 16)

Checked against LineageOS `lineage-23.0` sources: Settings -> System -> Gestures
-> Navigation mode contains the separate Circle to Search switch
(`search_gesture_press_hold` in `res/xml/system_navigation_gesture_settings.xml`).
Gesture navigation settings separately expose the corner-swipe assistant switch
(`assistant_gesture_corner_swipe` in `res/xml/gesture_navigation_settings.xml`).
Russian labels are "Обвести и найти" and "Жест вызова помощника".

Launcher3's `quickstep/inputconsumers/NavHandleLongPressHandler.java` invokes
`ContextualSearchInvoker` for a handle long press, rather than routing through the
selected voice assistant. Disabling that search gesture must not be described as
automatically remapping the same long press to our assistant. Corner swipe is the
standard configurable assistant gesture. Supporting the identical long press
would require launcher/ROM support or a separate authorized system change; an
ordinary assistant APK update does not change this dispatch.

Sources:
- https://github.com/LineageOS/android_packages_apps_Settings/blob/lineage-23.0/res/xml/system_navigation_gesture_settings.xml
- https://github.com/LineageOS/android_packages_apps_Settings/blob/lineage-23.0/res/xml/gesture_navigation_settings.xml
- https://github.com/LineageOS/android_packages_apps_Launcher3/blob/lineage-23.0/quickstep/src/com/android/quickstep/inputconsumers/NavHandleLongPressHandler.java
