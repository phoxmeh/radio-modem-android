package app.radiomodem.schedule

/** What a chart shows, used for colouring and for the chart-type filter. */
enum class ChartType(val label: String) {
    Analysis("Analysis"), Forecast("Forecast"), Satellite("Satellite"), Waves("Waves"), Ice("Ice"), Ocean("Sea temp"), Other("Other");

    companion object {
        /** Keyword classification of an English chart title; the order matters (a "wave forecast" is Waves, not Forecast). */
        fun of(title: String): ChartType {
            val t = title.uppercase()
            return when {
                Regex("SATELLITE|MSAT|CLOUD").containsMatchIn(t) -> Satellite
                Regex("\\bICE\\b|ICEBERG|SEA ICE").containsMatchIn(t) -> Ice
                Regex("WAVE|SEA STATE|SWELL|\\bSEAS\\b").containsMatchIn(t) -> Waves
                Regex("SEA SURFACE TEMP|\\bSST\\b|WATER TEMP|CURRENT|SURFACE TEMP").containsMatchIn(t) -> Ocean
                Regex("FORECAST|PROG|PROGNOSIS|OUTLOOK").containsMatchIn(t) -> Forecast
                Regex("ANALYSIS|\\bANAL\\b|CHART|OBSERVATION|OBS\\b|STREAMLINE|FRONTS|DEPRESSION").containsMatchIn(t) -> Analysis
                else -> Other
            }
        }
    }
}

/**
 * One scheduled transmission. [minute] is the UTC start (minutes since midnight), daily unless [days] limits it
 * (ISO day numbers, 1 = Monday .. 7 = Sunday). [onlyKHz] is set where each frequency carries its own schedule.
 */
data class Broadcast(
    val minute: Int, val title: String, val lpm: Int = 120, val ioc: Int = 576,
    val days: Set<Int>? = null, val onlyKHz: Double? = null,
) {
    val type: ChartType get() = ChartType.of(title)
    fun runsOn(isoDay: Int) = days == null || isoDay in days
}

data class StationSchedule(val stationId: String, val broadcasts: List<Broadcast>, val note: String = "")

/** Parsed schedules plus a human description of where they came from. */
data class ScheduleSet(val published: String, val schedules: Map<String, StationSchedule>) {
    /** Newer data wins per station. */
    operator fun plus(other: ScheduleSet) = ScheduleSet(other.published.ifEmpty { published }, schedules + other.schedules)
}
