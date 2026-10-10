package local.voice

import java.util.Locale

/** Whole requests for the phone's current player, not music search or quoted dictation. */
internal object NowPlayingRequest {
    private const val NOW = "(?:сейчас|щас|счас)"
    private const val SONG = "(?:песня|музыка|трек|композиция)"
    private val requests = listOf(
        "что(?: $NOW)?(?: у меня)? играет(?: за $SONG)?",
        "что за $SONG(?: $NOW)? играет",
        "(?:какая (?:песня|музыка|композиция)|какой трек)(?: $NOW)? играет",
        "как называется(?: (?:эта|текущая))? $SONG(?: которая(?: $NOW)? играет)?",
        "(?:кто исполняет|кто поет|кто поёт)(?: эту песню| этот трек)?",
        "(?:назови|скажи)(?: название)? (?:текущий трек|текущую песню|текущую композицию)",
        "(?:название|исполнитель) (?:текущего трека|текущей песни|текущей композиции)",
        "что я(?: $NOW)? слушаю"
    ).map { Regex("(?:$it)(?: у меня| в плеере| в проигрывателе)?") }

    fun matches(text: String): Boolean {
        val value = text.lowercase(Locale.ROOT).replace('ё', 'е').trim().trimEnd('.', '!', '?')
            .replace(Regex("\\s+"), " ")
        return requests.any { it.matches(value) }
    }
}
