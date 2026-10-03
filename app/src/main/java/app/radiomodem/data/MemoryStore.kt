package app.radiomodem.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saved frequencies as one JSON file (small data, same item format as export). Writes are atomic. */
class MemoryStore(private val file: File) {
    /**
     * Loads memories. First run seeds the starter lists; when the built-in lists have been revised (a higher
     * [SeedMemories.VERSION]) the built-in entries are refreshed in place and new ones added. Entries you created
     * yourself are never touched.
     */
    fun load(): List<Memory> {
        if (!file.exists()) return SeedMemories.all.also { save(it) }
        val items: List<Memory>
        val seedVersion: Int
        try {
            val text = file.readText().trim()
            if (text.startsWith("[")) { items = Memory.listFromJson(text); seedVersion = 0 }
            else { items = Memory.listFromJson(text); seedVersion = JSONObject(text).optInt("seedVersion", 0) }
        } catch (e: Exception) { return emptyList() }
        if (seedVersion >= SeedMemories.VERSION) return items
        return refreshSeeds(items).also { save(it) }
    }

    fun save(items: List<Memory>) {
        val json = JSONObject().put("version", 1).put("seedVersion", SeedMemories.VERSION)
            .put("items", JSONArray(items.map { it.toJson() })).toString(1)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        /** Built-in entries are replaced by the current definitions (keeping their ids); obsolete ones are dropped. */
        fun refreshSeeds(existing: List<Memory>): List<Memory> {
            val current = SeedMemories.all.associateBy { it.seedKey }
            val oldIds = existing.filter { it.seedKey != null }.associate { it.seedKey!! to it.id }
            val user = existing.filter { it.seedKey == null }
            return user + SeedMemories.all.map { m -> oldIds[m.seedKey]?.let { m.copy(id = it) } ?: m }
        }
    }
}
