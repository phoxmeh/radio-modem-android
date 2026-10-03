package app.radiomodem.data

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.io.File

class MemoryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = MemoryStore(File(app.filesDir, "memories.json"))
    val items = mutableStateListOf<Memory>().apply { addAll(store.load()) }
    var query by mutableStateOf("")
    var groupFilter by mutableStateOf<String?>(null)

    val groups: List<String> get() = items.map { it.group }.filter { it.isNotEmpty() }.distinct().sorted()

    val visible: List<Memory> get() {
        val q = query.trim().lowercase()
        return items.filter { m ->
            (groupFilter == null || m.group == groupFilter) &&
                (q.isEmpty() || m.name.lowercase().contains(q) || m.notes.lowercase().contains(q) ||
                    m.mode.lowercase().contains(q) || (m.modem ?: "").lowercase().contains(q) ||
                    "%.3f".format(m.freqHz / 1e6).contains(q))
        }.sortedWith(compareBy({ it.group.lowercase() }, { if (" · " in it.name) it.name.substringBefore(" · ").lowercase() else "" }, { it.freqHz }))
    }

    fun upsert(m: Memory) {
        val i = items.indexOfFirst { it.id == m.id }
        if (i >= 0) items[i] = m else items.add(m)
        store.save(items.toList())
    }

    fun delete(m: Memory) { items.removeAll { it.id == m.id }; store.save(items.toList()) }

    /** Adds entries not already present (same name+frequency+mode). Returns how many were added. */
    fun import(newItems: List<Memory>): Int {
        var n = 0
        for (m in newItems) {
            if (items.any { it.name == m.name && it.freqHz == m.freqHz && it.mode == m.mode }) continue
            items.add(m.copy(id = java.util.UUID.randomUUID().toString())); n++
        }
        if (n > 0) store.save(items.toList())
        return n
    }

    fun importText(text: String): Int =
        import(if (text.trimStart().startsWith("[") || text.trimStart().startsWith("{")) Memory.listFromJson(text)
               else Memory.listFromCsv(text))

    /** Re-adds any starter entries that were deleted. */
    fun restoreStarters(): Int {
        val have = items.mapNotNull { it.seedKey }.toSet()
        val missing = SeedMemories.all.filter { it.seedKey !in have }
        items.addAll(missing)
        if (missing.isNotEmpty()) store.save(items.toList())
        return missing.size
    }

    fun exportJson() = Memory.listToJson(items.toList())
    fun exportCsv() = Memory.listToCsv(items.toList())
}
