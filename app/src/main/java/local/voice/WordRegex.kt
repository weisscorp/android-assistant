package local.voice

// JDK 21's default \b is ASCII-based; Android's regex engine uses Unicode words.
// Spell out the boundary so Russian commands have the same meaning in both runtimes.
private const val WORD_CHARACTER = "[\\p{L}\\p{M}\\p{N}\\p{Pc}\\u200c\\u200d]"
private const val WORD_BOUNDARY = "(?:(?<!$WORD_CHARACTER)(?=$WORD_CHARACTER)|(?<=$WORD_CHARACTER)(?!$WORD_CHARACTER))"

/** For language patterns with word boundaries, not escaped literals or character classes. */
internal fun wordRegex(pattern: String, vararg options: RegexOption): Regex =
    Regex(pattern.replace("\\b", WORD_BOUNDARY), options.toSet())
