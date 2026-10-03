package app.radiomodem.schedule

/** A broadcast placed on the timeline. [lane] separates overlapping broadcasts of the same station. */
data class Instance(val station: FaxStation, val broadcast: Broadcast, val startMs: Long, val endMs: Long, val lane: Int = 0) {
    val title get() = broadcast.title
    fun isOn(nowMs: Long) = nowMs in startMs until endMs
}

object ScheduleEngine {
    const val MINUTE = 60_000L
    const val DAY = 24 * 60 * MINUTE
    private const val MIN_DURATION = 3
    private const val MAX_DURATION = 14   // a 120 LPM chart takes about 12 minutes

    /** ISO day of week (1 = Monday) for the UTC day starting at [dayStartMs]. 1 Jan 1970 was a Thursday. */
    fun isoDay(dayStartMs: Long) = (Math.floorMod(Math.floorDiv(dayStartMs, DAY) + 3, 7L) + 1).toInt()

    /**
     * How long each broadcast probably lasts: until the next one starts, within [MIN_DURATION]..[MAX_DURATION] minutes.
     * Schedules give only start times. Broadcasts with their own frequency are spaced against that frequency's list.
     */
    fun durations(broadcasts: List<Broadcast>): Map<Broadcast, Int> {
        val out = HashMap<Broadcast, Int>()
        for ((_, group) in broadcasts.groupBy { it.onlyKHz }) {
            val minutes = group.map { it.minute }.distinct().sorted()
            for (b in group) {
                val next = minutes.firstOrNull { it > b.minute } ?: (minutes.first() + 1440)
                out[b] = if (minutes.size == 1) MAX_DURATION else (next - b.minute).coerceIn(MIN_DURATION, MAX_DURATION)
            }
        }
        return out
    }

    /** Every broadcast of the given stations that overlaps [fromMs, toMs), sorted by start. */
    fun instances(stations: List<FaxStation>, schedules: Map<String, StationSchedule>, fromMs: Long, toMs: Long): List<Instance> {
        val out = mutableListOf<Instance>()
        for (station in stations) {
            val schedule = schedules[station.id] ?: continue
            val dur = durations(schedule.broadcasts)
            val mine = mutableListOf<Instance>()
            var day = Math.floorDiv(fromMs, DAY) * DAY - DAY
            while (day < toMs) {
                val iso = isoDay(day)
                for (b in schedule.broadcasts) {
                    if (!b.runsOn(iso)) continue
                    val start = day + b.minute * MINUTE
                    val end = start + (dur[b] ?: MAX_DURATION) * MINUTE
                    if (end > fromMs && start < toMs) mine += Instance(station, b, start, end)
                }
                day += DAY
            }
            out += assignLanes(mine.sortedBy { it.startMs })
        }
        return out.sortedWith(compareBy({ it.startMs }, { it.station.id }))
    }

    /** Greedy lane assignment: a broadcast goes in the first lane that is free when it starts. */
    private fun assignLanes(sorted: List<Instance>): List<Instance> {
        val laneEnd = mutableListOf<Long>()
        return sorted.map { i ->
            var lane = laneEnd.indexOfFirst { it <= i.startMs }
            if (lane < 0) { laneEnd += 0L; lane = laneEnd.lastIndex }
            laneEnd[lane] = i.endMs
            i.copy(lane = lane)
        }
    }

    /** The broadcast on air now, or the next to start. */
    fun nextUp(instances: List<Instance>, nowMs: Long) = instances.filter { it.endMs > nowMs }.minByOrNull { it.startMs }

    /** Frequencies this broadcast can be heard on: its own frequency if it has one, else those on air at that minute. */
    fun frequenciesFor(i: Instance): List<FaxFrequency> {
        val only = i.broadcast.onlyKHz
        if (only != null) return i.station.freqs.filter { it.kHz == only }.ifEmpty { listOf(FaxFrequency(only)) }
        return i.station.activeAt(i.broadcast.minute)
    }
}
