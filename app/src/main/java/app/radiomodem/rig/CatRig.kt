package app.radiomodem.rig

/**
 * Yaesu-style ASCII CAT ("FA014070000;") driven by a [RigProfile]. Not thread-safe by design:
 * callers (RigViewModel) serialize all access on one coroutine.
 */
class CatRig(val profile: RigProfile, private val link: SerialLink) {
    private val j = profile.json
    private val replyTimeoutMs = 400  // flrig FL891_WAIT_TIME

    private fun cmd(section: String, key: String): String = j.getJSONObject(section).getString(key)
    private fun regex(section: String) = Regex(j.getJSONObject(section).getString("replyRegex"))

    private fun expand(template: String, vararg args: Pair<String, Any>): String {
        var s = template
        for ((k, v) in args) {
            s = when (k) {
                "f9" -> s.replace("{f9}", "%09d".format(v as Long))
                "n3" -> s.replace("{n3}", "%03d".format(v as Int))
                "n2" -> s.replace("{n2}", "%02d".format(v as Int))
                else -> s.replace("{$k}", v.toString())
            }
        }
        return s
    }

    private fun query(command: String): String {
        link.purge()
        link.write(command)
        return link.readUntil(profile.terminator, replyTimeoutMs)
    }

    private fun send(command: String) {
        link.write(command)
        if (profile.serial.postWriteDelayMs > 0) Thread.sleep(profile.serial.postWriteDelayMs.toLong())
    }

    /** True if the rig answers the identify command. */
    fun identify(): Boolean = regex("identify").containsMatchIn(query(cmd("identify", "send")))

    fun getFrequency(): Long? =
        regex("frequency").findAll(query(cmd("frequency", "get"))).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()

    fun setFrequency(hz: Long) = send(expand(cmd("frequency", "set"), "f9" to hz))

    fun getMode(): Int? {
        val code = regex("mode").findAll(query(cmd("mode", "get"))).lastOrNull()?.groupValues?.get(1) ?: return null
        return profile.modes.indexOfFirst { it.code == code }.takeIf { it >= 0 }
    }

    fun setMode(modeIdx: Int) = send(expand(cmd("mode", "set"), "code" to profile.modes[modeIdx].code))

    /** Index into the current mode's bandwidth table, or null if the mode has no selectable width. */
    fun getBandwidth(modeIdx: Int): Int? {
        if (hasNoBandwidth(modeIdx)) return null
        val table = profile.bwTableFor(modeIdx) ?: return null
        val m = regex("bandwidth").findAll(query(cmd("bandwidth", "get"))).lastOrNull() ?: return null
        val value = m.groupValues[2].toIntOrNull() ?: return null
        val idx = table.values.indexOf(value)
        return if (idx >= 0) idx else (table.values.size - 1)  // flrig falls back to the last entry
    }

    fun setBandwidth(modeIdx: Int, bwIdx: Int) {
        if (hasNoBandwidth(modeIdx)) return
        val mode = profile.modes[modeIdx]
        val table = profile.bwTableFor(modeIdx) ?: return
        val bw = j.getJSONObject("bandwidth")
        val narrowSet = bw.optString("narrowSet")
        if (narrowSet.isNotEmpty()) {  // some radios have a separate narrow-filter switch, some do not
            val narrowMax = bw.getJSONObject("narrowMaxIndexBySideband").optInt(mode.group, -1)
            send(expand(narrowSet, "narrow" to if (bwIdx <= narrowMax) 1 else 0))
        }
        send(expand(bw.getString("set"), "n2" to table.values[bwIdx]))
    }

    private fun hasNoBandwidth(modeIdx: Int): Boolean {
        val list = j.getJSONObject("bandwidth").getJSONArray("noBandwidthModes").strings()
        return profile.modes[modeIdx].name in list
    }

    fun getPower(): Int? =
        regex("power").findAll(query(cmd("power", "get"))).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    fun setPower(watts: Int) =
        send(expand(cmd("power", "set"), "n3" to watts.coerceIn(profile.powerMin, profile.powerMax)))

    /** S-meter scaled 0..100 using the profile's divisor (flrig: raw / 2.56). */
    fun getSmeter(): Int? {
        val raw = regex("smeter").findAll(query(cmd("smeter", "get"))).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val div = (j.getJSONObject("smeter").optDouble("scaleDivisor", 1.0))
        return (raw / div).toInt().coerceIn(0, 100)
    }

    fun close() = link.close()
}
