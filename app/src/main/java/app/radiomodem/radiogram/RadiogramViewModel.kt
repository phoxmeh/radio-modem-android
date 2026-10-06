package app.radiomodem.radiogram

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.radiomodem.settings.AppSettings
import kotlinx.coroutines.launch

class RadiogramViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RadiogramRepository(app)
    private val reminders = RadiogramReminders(app)
    private val settings = AppSettings.get(app)

    var week by mutableStateOf(repo.load()); private set
    var refreshing by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var nowMs by mutableLongStateOf(System.currentTimeMillis())
    /** Keys of broadcasts that have a reminder set. */
    val reminded = mutableStateListOf<String>().apply { addAll(reminders.all().map { it.key }) }

    val leadMin: Int get() = settings.radiogramLeadMin.toInt()
    fun view() = RadiogramRepository.view(week, nowMs)
    fun tick() { nowMs = System.currentTimeMillis() }

    /** Refreshes when the saved copy is old; [onSlots] gets the table so saved-frequency entries can be added. */
    fun refresh(force: Boolean = false, onSlots: (List<RadiogramSlot>) -> Unit = {}) {
        if (refreshing) return
        if (!force && System.currentTimeMillis() - week.fetchedAtMs < 6 * 3_600_000L) return
        refreshing = true
        viewModelScope.launch {
            message = repo.refresh()
            week = repo.load()
            if (week.rowsFrom == "image") onSlots(week.slots)
            refreshing = false
        }
    }

    fun has(b: RadiogramBroadcast) = b.key in reminded

    fun toggleReminder(b: RadiogramBroadcast) {
        if (has(b)) { reminders.clear(b.key); reminded.remove(b.key) }
        else { reminders.set(b, leadMin); reminded.add(b.key) }
    }

    fun setLead(minutes: Int) { settings.radiogramLeadMin = minutes.toFloat(); reminders.rescheduleAll(minutes) }
    fun canBeExact() = reminders.canBeExact()

    /** Sets the alarms again, so reminders made before exact alarms were allowed become exact. */
    fun reschedule() = reminders.rescheduleAll(leadMin)
}
