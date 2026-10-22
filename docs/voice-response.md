# Local response pipeline

Short clock and track-switch requests use device tools directly. The track parser
matches a complete request (including short polite variants); dictation, negation,
questions about the next song and combined tasks retain their own routes.
Media controls still report a verified state change, or explicitly say that only
the command was sent. Players may take up to three seconds to confirm an action.

Current-track questions still require a model-selected `media/info` read. A complete
current-track question omits unrelated previous dialogue from its model prompt.
The reader formats title, artist, playback state, missing permissions and ambiguous
players directly. The app does not run a second inference to paraphrase those facts.
Both the actual tool observation and the formatted response remain in the journal.

Conversational generation uses constrained JSON with `answer` first and compact
`topics`/`points` after it. JNI delivers the complete answer string as soon as it
closes, correctly decoding escaped quotes, Unicode and braces in the string.
Generation continues on the model worker to finish the memory note. Main-thread
callbacks check the operation ID; each request delivers at most one answer.
Cancellation or a failed memory tail preserves the last completed note. A new
request can cancel an unfinished note; the bounded interaction journal still records
the interaction. No additional LLM call is made to update this compact note.

Journal events distinguish `llm_answer_ready` from full `llm_result` completion,
and `tool_answer_formatted` identifies metadata answers without a second inference.
`generation_elapsed_ms` includes prefill and generation but not model loading.
These events help distinguish recognition, inference, player confirmation and
speech startup; emulator timings must not be presented as phone performance.

`SpokenReply.text` expands decimal dot/comma literals into Russian fractions for
both synthesis engines: `26.7 градусов` becomes
`двадцать шесть и семь десятых градуса`. Display text stays numeric. Fractional
leading zeroes, signs and unit agreement are preserved; URLs, addresses, full dates,
times and identifiers are excluded. The expanded speech payload retains its
3000-character limit and explicit truncation message.

Validation: JVM number/routing tests; `python3 tools/test_answer_prefix.py` for the
production JSON-prefix reader; emulator-only `MemoryAnswerLatencyTest`,
`MediaCommandLatencyTest`, `NowPlayingConversationTest` and interaction-journal tests.
After JNI changes, every catalog artifact must pass `CatalogRuntimeTest` again
before refreshing its `validatedBinding` proof. These tests need a synthetic media
fixture and local GGUF weights, and never require a physical phone or audio checks.

Simple two-operand arithmetic is calculated by ArithmeticReply before brain/server
routing, in LOCAL/AUTO/HERMES alike. It accepts Russian integers 0–999 or bounded
numeric decimals and +, −, multiplication, division; it never evals input. Division
by zero has an explicit answer; repeating decimals are marked approximate (8 places).
Only a complete expression matches: dictation, unrelated prose, missing operands and
combined commands continue through their existing routes. No operands are invented
from recognition fragments. ArithmeticReplyTest and ArithmeticConversationTest verify
results, routing independent of models, and negative/dictation cases.

Calendar speech normalization (0.42.7) runs before decimal expansion for both TTS
engines. Valid HH:mm / HH:mm:ss become hours, minutes, seconds; HH.mm is treated
as time in clock context or next to a date, never as a blanket decimal rule.
Named dates and valid dd.MM.yyyy use ordinal days and genitive month/year names;
dd.MM requires calendar context or an adjacent clock time. After «сегодня»/«дата»
the day is nominative; directly after «на» it is accusative (same neuter form).
Clock minutes/seconds after «в»/«на» use «одну минуту», not «один минута».
Zero minutes at an exact hour are omitted. Display strings remain unchanged.

Example: «на 15.00 18 сентября» → «на пятнадцать часов восемнадцатого сентября».
«Сегодня 18 сентября» → «Сегодня восемнадцатое сентября».
Ordinary decimals, version strings, addresses and URLs retain their own rules;
invalid calendar dates are not invented. SpokenCalendarTest verifies these cases,
including leap dates, date/year endings, minute gender, exact hours and seconds.
SpeechCalendarRuntimeTest checks the same payload through native Silero and Piper
synthesis on an explicitly selected silent emulator, without AudioTrack/microphone.
