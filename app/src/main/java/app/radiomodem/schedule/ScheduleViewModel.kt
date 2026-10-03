package app.radiomodem.schedule

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.radiomodem.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val WINDOW_HOURS = 30L       // timeline length: two hours back, 28 ahead
        const val HOURS_BEFORE_NOW = 2L
    }

    private val repo = ScheduleRepository(app)
    val settings = AppSettings.get(app)

    var data by mutableStateOf(ScheduleSet("", emptyMap())); private set
    var source by mutableStateOf(""); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var nowMs by mutableLongStateOf(System.currentTimeMillis()); private set

    init {
        viewModelScope.launch {
            val (set, desc) = withContext(Dispatchers.IO) { repo.load() to repo.describe() }
            data = set; source = desc; loading = false
        }
        viewModelScope.launch { while (true) { delay(15_000); nowMs = System.currentTimeMillis() } }
    }

    // ---- selection and filters (persisted) ----------------------------------------------------------------------

    val selectedIds: Set<String> get() = settings.scheduleStations.split(',').filter { it.isNotBlank() }.toSet()
    fun setStations(ids: Set<String>) { settings.scheduleStations = ids.joinToString(",") }
    fun toggleStation(id: String) = setStations(if (id in selectedIds) selectedIds - id else selectedIds + id)

    val types: Set<ChartType> get() = settings.scheduleTypes.split(',').mapNotNull { n -> ChartType.values().firstOrNull { it.name == n } }.toSet()
    fun toggleType(t: ChartType) {
        val now = types
        settings.scheduleTypes = (if (t in now) now - t else now + t).joinToString(",") { it.name }
    }

    /** Stations the user picked that have a parsed schedule, in catalog order. */
    val stations: List<FaxStation> get() = FaxStations.all.filter { it.id in selectedIds && data.schedules.containsKey(it.id) }

    // ---- timeline ----------------------------------------------------------------------------------------------

    val windowStartMs: Long get() = Math.floorDiv(nowMs, 3_600_000L) * 3_600_000L - HOURS_BEFORE_NOW * 3_600_000L
    val windowEndMs: Long get() = windowStartMs + WINDOW_HOURS * 3_600_000L

    fun instances(): List<Instance> {
        val allowed = types
        return ScheduleEngine.instances(stations, data.schedules.mapValues { it.value }, windowStartMs, windowEndMs)
            .filter { it.broadcast.type in allowed }
    }

    // ---- update -------------------------------------------------------------------------------------------------

    fun refresh() {
        if (refreshing) return
        refreshing = true; message = "Checking NOAA for newer schedules…"
        viewModelScope.launch {
            val report = try { repo.refresh() } catch (e: Exception) { null }
            if (report == null) message = "Update failed (no connection?)"
            else {
                message = report.summary()
                withContext(Dispatchers.IO) { repo.load() to repo.describe() }.let { (set, desc) -> data = set; source = desc }
                settings.scheduleLastCheck = System.currentTimeMillis().toString()
            }
            refreshing = false
        }
    }
}
