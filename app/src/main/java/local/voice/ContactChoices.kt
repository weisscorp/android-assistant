package local.voice

import java.util.Locale

/** Matching never chooses an arbitrary row when names or phone numbers are ambiguous. */
object ContactChoices {
    data class Entry(val id: Long, val name: String, val number: String, val kind: String, val lookup: String = "")
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е').trim().replace(Regex("\\s+"), " ")
    fun matches(query: String, entries: List<Entry>): List<Entry> {
        val name = normalized(query)
        if (name.isEmpty()) return emptyList()
        val exact = entries.filter { normalized(it.name) == name }
        val result = if (exact.isNotEmpty()) exact else entries.filter { entry ->
            val words = normalized(entry.name).split(' ')
            name.split(' ').all { it in words }
        }
        return result.mapNotNull { entry -> VoiceCommands.phoneNumber(entry.number)?.let { entry.copy(number = it) } }
            .distinctBy { it.id to it.number }
            .sortedWith(compareBy({ normalized(it.name) }, { it.kind }, { it.number }))
    }
}
