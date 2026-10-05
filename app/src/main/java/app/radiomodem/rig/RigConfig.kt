package app.radiomodem.rig

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One saved rig: which radio model it is, how it is wired, and how it should be set when tuning.
 * Several can be saved (for example the same model on two cables, or different radios) and one is active.
 */
data class RigConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Id of a bundled [RigProfile] (the JSON file in assets/rigs named after it). */
    val profileId: String = "FT-891",
    val usbVendor: Int = -1,
    val usbProduct: Int = -1,
    val usbPort: Int = 0,
    /** 0 = the model's default CAT rate. */
    val baud: Int = 0,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    /** 0 none, 1 odd, 2 even (usb-serial-for-android constants). */
    val parity: Int = 0,
    /** none | rtscts | xonxoff */
    val flow: String = "none",
    /** Line state to set when the port opens: leave | low | high. Left alone by default because on many radios these keys PTT. */
    val dtr: String = "leave",
    val rts: String = "leave",
    /** Mode used when tuning a saved frequency or schedule entry ("" = leave the radio's mode alone). */
    val defaultMode: String = "USB",
    /** Bandwidth label from the mode's table ("" = leave the radio's filter alone). */
    val defaultBw: String = "",
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("profileId", profileId)
        put("usbVendor", usbVendor); put("usbProduct", usbProduct); put("usbPort", usbPort); put("baud", baud)
        put("dataBits", dataBits); put("stopBits", stopBits); put("parity", parity); put("flow", flow)
        put("dtr", dtr); put("rts", rts); put("defaultMode", defaultMode); put("defaultBw", defaultBw)
    }

    companion object {
        fun fromJson(o: JSONObject) = RigConfig(
            id = o.getString("id"), name = o.getString("name"), profileId = o.optString("profileId", "FT-891"),
            usbVendor = o.optInt("usbVendor", -1), usbProduct = o.optInt("usbProduct", -1), usbPort = o.optInt("usbPort", 0),
            baud = o.optInt("baud", 0), dataBits = o.optInt("dataBits", 8), stopBits = o.optInt("stopBits", 1),
            parity = o.optInt("parity", 0), flow = o.optString("flow", "none"), dtr = o.optString("dtr", "leave"),
            rts = o.optString("rts", "leave"), defaultMode = o.optString("defaultMode", "USB"), defaultBw = o.optString("defaultBw", ""),
        )
    }
}

/** Saved rigs and the active one, in a small SharedPreferences file. */
class RigStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("rig_list", Context.MODE_PRIVATE)

    fun load(legacy: () -> RigConfig): List<RigConfig> {
        val text = prefs.getString("list", null)
        val list = text?.let { runCatching { JSONArray(it).let { a -> (0 until a.length()).map { RigConfig.fromJson(a.getJSONObject(it)) } } }.getOrNull() }
        if (!list.isNullOrEmpty()) return list
        return listOf(legacy()).also { save(it) }  // first run, or an upgrade from the single-rig version
    }

    fun save(list: List<RigConfig>) { prefs.edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply() }

    var activeId: String
        get() = prefs.getString("active", "") ?: ""
        set(v) { prefs.edit().putString("active", v).apply() }
}

/** Radio models bundled in assets/rigs (one JSON file per model). */
object RigProfiles {
    private var cache: Map<String, RigProfile>? = null

    private fun all(ctx: Context): Map<String, RigProfile> = cache ?: (ctx.assets.list("rigs") ?: emptyArray())
        .filter { it.endsWith(".json") }
        .mapNotNull { f -> runCatching { RigProfile.parse(ctx.assets.open("rigs/$f").bufferedReader().use { it.readText() }) }.getOrNull() }
        .sortedBy { it.name }.associateBy { it.id }.also { cache = it }

    /** (id, display name) of every bundled radio. */
    fun available(ctx: Context): List<Pair<String, String>> = all(ctx).values.map { it.id to it.name }

    fun load(ctx: Context, id: String): RigProfile = all(ctx)[id] ?: all(ctx).values.first()
}
