package local.voice

import android.content.Context
import org.json.JSONObject

/** User-taught identities; never populated by a language model or an application screen. */
class PeopleMemory(private val context: Context) {
    private val prefs = context.getSharedPreferences("people", 0)
    data class Person(val alias: String, val lookup: String = "", val name: String = "", val telegram: String = "")
    fun get(alias: String): Person? = runCatching {
        val key = PersonNames.key(alias)
        val data = JSONObject(prefs.getString(key, null) ?: return null)
        Person(key, data.optString("lookup"), data.optString("name"), data.optString("telegram"))
    }.getOrNull()
    fun contact(alias: String, entry: ContactChoices.Entry) {
        require(PersonNames.valid(alias) && entry.lookup.isNotBlank())
        val old = get(alias)
        save(Person(PersonNames.key(alias), entry.lookup, entry.name, old?.telegram.orEmpty()))
    }
    fun telegram(alias: String, target: String) {
        require(PersonNames.valid(alias) && target.matches(Regex("@?[\\p{L}\\p{N}][\\p{L}\\p{N} ._'’-]{0,99}")))
        val old = get(alias)
        save(Person(PersonNames.key(alias), old?.lookup.orEmpty(), old?.name.orEmpty(), target))
    }
    private fun save(person: Person) {
        check(prefs.contains(person.alias) || prefs.all.size < 100) { "Память контактов заполнена." }
        check(prefs.edit().putString(person.alias, JSONObject().put("lookup", person.lookup).put("name", person.name)
            .put("telegram", person.telegram).toString()).commit())
        HomeLink.kick(context)
    }
    fun forget(alias: String) { check(prefs.edit().remove(PersonNames.key(alias)).commit()); HomeLink.kick(context) }
    fun list(): List<Person> = prefs.all.keys.sorted().mapNotNull(::get)
    fun message(): String = list().joinToString("\n") { person ->
        "${person.alias}: ${person.name.ifBlank { "контакт не выбран" }}${person.telegram.takeIf { it.isNotBlank() }?.let { "; Telegram: $it" }.orEmpty()}"
    }.ifBlank { "Связей пока нет. Скажите: запомни жена это Мария Иванова. Отдельно: запомни телеграм жены это @username." }
}
