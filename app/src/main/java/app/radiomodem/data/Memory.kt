package app.radiomodem.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A saved frequency. [freqHz] is the radio's dial frequency (USB/LSB dial, not the carrier).
 * [mode] and [bandwidth] are rig vocabulary (e.g. "DATA-U", "3000"); [modem]/[carrierHz] restore the decoder.
 */
data class Memory(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val group: String = "",
    val freqHz: Long,
    val mode: String = "USB",
    val bandwidth: String? = null,
    val modem: String? = null,
    val carrierHz: Int? = null,
    val notes: String = "",
    /** Stable key of built-in starter entries, so they can be restored without duplicating. */
    val seedKey: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("group", group); put("freqHz", freqHz); put("mode", mode)
        bandwidth?.let { put("bandwidth", it) }; modem?.let { put("modem", it) }
        carrierHz?.let { put("carrierHz", it) }
        if (notes.isNotEmpty()) put("notes", notes)
        seedKey?.let { put("seedKey", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Memory(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            name = o.getString("name"), group = o.optString("group"),
            freqHz = o.getLong("freqHz"), mode = o.optString("mode", "USB"),
            bandwidth = o.optString("bandwidth").ifEmpty { null }, modem = o.optString("modem").ifEmpty { null },
            carrierHz = if (o.has("carrierHz")) o.getInt("carrierHz") else null,
            notes = o.optString("notes"), seedKey = o.optString("seedKey").ifEmpty { null },
        )

        fun listToJson(items: List<Memory>): String =
            JSONObject().put("version", 1).put("items", JSONArray(items.map { it.toJson() })).toString(1)

        fun listFromJson(text: String): List<Memory> {
            val t = text.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("items")
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        // ---- CSV: name,group,freq_hz,mode,bandwidth,modem,carrier_hz,notes -----------------------

        private val header = listOf("name", "group", "freq_hz", "mode", "bandwidth", "modem", "carrier_hz", "notes")

        fun listToCsv(items: List<Memory>): String = buildString {
            appendLine(header.joinToString(","))
            for (m in items) appendLine(listOf(m.name, m.group, m.freqHz.toString(), m.mode, m.bandwidth ?: "",
                m.modem ?: "", m.carrierHz?.toString() ?: "", m.notes).joinToString(",") { quote(it) })
        }

        private fun quote(s: String) =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        /** Parses the CSV written by [listToCsv]; column order is taken from the header row. */
        fun listFromCsv(text: String): List<Memory> {
            val rows = parseCsv(text).filter { r -> r.any { it.isNotBlank() } }
            if (rows.isEmpty()) return emptyList()
            val cols = rows[0].map { it.trim().lowercase() }
            fun get(r: List<String>, k: String) = cols.indexOf(k).let { if (it in r.indices) r[it].trim() else "" }
            return rows.drop(1).mapNotNull { r ->
                val name = get(r, "name"); val f = get(r, "freq_hz").toLongOrNull() ?: return@mapNotNull null
                if (name.isEmpty()) return@mapNotNull null
                Memory(name = name, group = get(r, "group"), freqHz = f,
                    mode = get(r, "mode").ifEmpty { "USB" }, bandwidth = get(r, "bandwidth").ifEmpty { null },
                    modem = get(r, "modem").ifEmpty { null }, carrierHz = get(r, "carrier_hz").toIntOrNull(),
                    notes = get(r, "notes"))
            }
        }

        internal fun parseCsv(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cur = StringBuilder()
            var inQuotes = false; var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                    c == '"' -> inQuotes = !inQuotes
                    !inQuotes && c == ',' -> { row.add(cur.toString()); cur.clear() }
                    !inQuotes && (c == '\n' || c == '\r') -> {
                        if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                        row.add(cur.toString()); cur.clear(); rows.add(row); row = mutableListOf()
                    }
                    else -> cur.append(c)
                }
                i++
            }
            if (cur.isNotEmpty() || row.isNotEmpty()) { row.add(cur.toString()); rows.add(row) }
            return rows
        }
    }
}
