package org.tasks.ui

import android.content.Context
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import com.todoroo.andlib.utility.DateUtilities.now
import com.todoroo.astrid.alarms.AlarmService
import com.todoroo.astrid.api.CaldavFilter
import com.todoroo.astrid.api.Filter
import com.todoroo.astrid.api.GtasksFilter
import com.todoroo.astrid.dao.TaskDao
import com.todoroo.astrid.data.SyncFlags
import com.todoroo.astrid.data.Task
import com.todoroo.astrid.data.Task.Companion.NOTIFY_MODE_FIVE
import com.todoroo.astrid.data.Task.Companion.NOTIFY_MODE_NONSTOP
import com.todoroo.astrid.data.Task.Companion.createDueDate
import com.todoroo.astrid.data.Task.Companion.hasDueTime
import com.todoroo.astrid.data.Task.Companion.isRepeatAfterCompletion
import com.todoroo.astrid.data.Task.Companion.withoutFrom
import com.todoroo.astrid.gcal.GCalHelper
import com.todoroo.astrid.service.TaskCompleter
import com.todoroo.astrid.service.TaskDeleter
import com.todoroo.astrid.service.TaskMover
import com.todoroo.astrid.timers.TimerPlugin
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.fortuna.ical4j.model.Recur
import org.tasks.R
import org.tasks.Strings
import org.tasks.analytics.Firebase
import org.tasks.calendars.CalendarEventProvider
import org.tasks.data.Alarm
import org.tasks.data.Alarm.Companion.TYPE_RANDOM
import org.tasks.data.Alarm.Companion.TYPE_REL_END
import org.tasks.data.Alarm.Companion.TYPE_REL_START
import org.tasks.data.Alarm.Companion.whenDue
import org.tasks.data.Alarm.Companion.whenOverdue
import org.tasks.data.Alarm.Companion.whenStarted
import org.tasks.data.CaldavDao
import org.tasks.data.CaldavTask
import org.tasks.data.GoogleTask
import org.tasks.data.GoogleTaskDao
import org.tasks.data.Location
import org.tasks.data.LocationDao
import org.tasks.data.TagDao
import org.tasks.data.TagData
import org.tasks.data.TagDataDao
import org.tasks.date.DateTimeUtils.toDateTime
import org.tasks.location.GeofenceApi
import org.tasks.preferences.PermissionChecker
import org.tasks.preferences.Preferences
import org.tasks.repeats.RecurrenceUtils.newRecur
import org.tasks.time.DateTime
import org.tasks.time.DateTimeUtils.currentTimeMillis
import org.tasks.time.DateTimeUtils.startOfDay
import timber.log.Timber
import java.text.ParseException
import javax.inject.Inject

@HiltViewModel
class TaskEditViewModel @Inject constructor(
        @ApplicationContext private val context: Context,
        private val taskDao: TaskDao,
        private val taskDeleter: TaskDeleter,
        private val timerPlugin: TimerPlugin,
        private val permissionChecker: PermissionChecker,
        private val calendarEventProvider: CalendarEventProvider,
        private val gCalHelper: GCalHelper,
        private val taskMover: TaskMover,
        private val locationDao: LocationDao,
        private val geofenceApi: GeofenceApi,
        private val tagDao: TagDao,
        private val tagDataDao: TagDataDao,
        private val preferences: Preferences,
        private val googleTaskDao: GoogleTaskDao,
        private val caldavDao: CaldavDao,
        private val taskCompleter: TaskCompleter,
        private val alarmService: AlarmService,
        private val taskListEvents: TaskListEventBus,
        private val mainActivityEvents: MainActivityEventBus,
        private val firebase: Firebase? = null,
) : ViewModel() {

    private var cleared = false

    fun setup(
            task: Task,
            list: Filter,
            location: Location?,
            tags: List<TagData>,
            alarms: List<Alarm>,
    ) {
        this.task = task
        isNew = task.isNew
        originalList = list
        selectedList = list
        originalLocation = location
        originalTags = tags.toList()
        selectedTags = ArrayList(tags)
        originalAlarms =
            if (isNew) {
                ArrayList<Alarm>().apply {
                    if (task.isNotifyAtStart) {
                        add(whenStarted(0))
                    }
                    if (task.isNotifyAtDeadline) {
                        add(whenDue(0))
                    }
                    if (task.isNotifyAfterDeadline) {
                        add(whenOverdue(0))
                    }
                    if (task.randomReminder > 0) {
                        add(Alarm(0, task.randomReminder, TYPE_RANDOM))
                    }
                }
            } else {
                alarms
            }
        selectedAlarms.value = originalAlarms
        if (isNew && permissionChecker.canAccessCalendars()) {
            originalCalendar = preferences.defaultCalendar
        }
        eventUri = task.calendarURI
    }

    lateinit var task: Task
        private set

    var title: String? = null
        get() = field ?: task.title

    var completed: Boolean? = null
        get() = field ?: task.isCompleted

    var dueDate: Long? = null
        get() = field ?: task.dueDate
        set(value) {
            field = when {
                value == null -> null
                value == 0L -> 0
                hasDueTime(value) -> createDueDate(Task.URGENCY_SPECIFIC_DAY_TIME, value)
                else -> createDueDate(Task.URGENCY_SPECIFIC_DAY, value)
            }
        }

    var priority: Int? = null
        get() = field ?: task.priority

    var description: String? = null
        get() = field ?: task.notes.stripCarriageReturns()

    var hideUntil: Long? = null
        get() = field ?: task.hideUntil
        set(value) {
            field = when {
                value == null -> null
                value == 0L -> 0
                hasDueTime(value) ->
                    value.toDateTime().withSecondOfMinute(1).withMillisOfSecond(0).millis
                else -> value.startOfDay()
            }
        }

    var recurrence: String? = null
        get() = field ?: task.recurrence

    var repeatUntil: Long? = null
        get() = field ?: task.repeatUntil

    var repeatAfterCompletion: Boolean? = null
        get() = field ?: task.repeatAfterCompletion()
        set(value) {
            field = value
            if (value == true) {
                if (!recurrence.isRepeatAfterCompletion()) {
                    recurrence += ";FROM=COMPLETION"
                }
            } else if (recurrence.isRepeatAfterCompletion()) {
                recurrence = recurrence.withoutFrom()
            }
        }

    var recur: Recur?
        get() = if (recurrence.isNullOrBlank()) {
            null
        } else {
            val rrule = newRecur(recurrence!!)
            repeatUntil?.takeIf { it > 0 }?.let {
                rrule.until = DateTime(it).toDate()
            }
            rrule
        }
        set(value) {
            if (value == null) {
                recurrence = ""
                repeatUntil = 0
                return
            }
            val copy = try {
                newRecur(value.toString())
            } catch (e: ParseException) {
                recurrence = ""
                repeatUntil = 0
                return
            }
            repeatUntil = DateTime.from(copy.until).millis
            if (repeatUntil ?: 0 > 0) {
                copy.until = null
            }
            var result = copy.toString()
            if (repeatAfterCompletion!! && result.isNotBlank()) {
                result += ";FROM=COMPLETION"
            }
            recurrence = result
        }

    var originalCalendar: String? = null
        private set(value) {
            field = value
            selectedCalendar = value
        }

    var selectedCalendar: String? = null

    var eventUri: String? = null

    var isNew: Boolean = false
        private set

    var timerStarted: Long
        get() = task.timerStart
        set(value) {
            task.timerStart = value
        }

    var estimatedSeconds: Int? = null
        get() = field ?: task.estimatedSeconds

    var elapsedSeconds: Int? = null
        get() = field ?: task.elapsedSeconds

    private lateinit var originalList: Filter

    var selectedList: Filter? = null

    var originalLocation: Location? = null
        private set(value) {
            field = value
            selectedLocation = value
        }

    var selectedLocation: Location? = null

    private lateinit var originalTags: List<TagData>

    lateinit var selectedTags: ArrayList<TagData>

    var newSubtasks = ArrayList<Task>()

    private lateinit var originalAlarms: List<Alarm>

    var selectedAlarms = MutableStateFlow(emptyList<Alarm>())

    var ringNonstop: Boolean? = null
        get() = field ?: task.isNotifyModeNonstop
        set(value) {
            field = value
            if (value == true) {
                ringFiveTimes = false
            }
        }

    var ringFiveTimes:Boolean? = null
        get() = field ?: task.isNotifyModeFive
        set(value) {
            field = value
            if (value == true) {
                ringNonstop = false
            }
        }

    fun hasChanges(): Boolean =
        (task.title != title || (isNew && title?.isNotBlank() == true)) ||
                task.isCompleted != completed ||
                task.dueDate != dueDate ||
                task.priority != priority ||
                if (task.notes.isNullOrBlank()) {
                    !description.isNullOrBlank()
                } else {
                    task.notes != description
                } ||
                task.hideUntil != hideUntil ||
                if (task.recurrence.isNullOrBlank()) {
                    !recurrence.isNullOrBlank()
                } else {
                    task.recurrence != recurrence
                } ||
                task.repeatAfterCompletion() != repeatAfterCompletion ||
                task.repeatUntil != repeatUntil ||
                originalCalendar != selectedCalendar ||
                if (task.calendarURI.isNullOrBlank()) {
                    !eventUri.isNullOrBlank()
                } else {
                    task.calendarURI != eventUri
                } ||
                task.elapsedSeconds != elapsedSeconds ||
                task.estimatedSeconds != estimatedSeconds ||
                originalList != selectedList ||
                originalLocation != selectedLocation ||
                originalTags.toHashSet() != selectedTags.toHashSet() ||
                newSubtasks.isNotEmpty() ||
                getRingFlags() != when {
                    task.isNotifyModeFive -> NOTIFY_MODE_FIVE
                    task.isNotifyModeNonstop -> NOTIFY_MODE_NONSTOP
                    else -> 0
                } ||
                originalAlarms.toHashSet() != selectedAlarms.value.toHashSet()

    @MainThread
    suspend fun save(remove: Boolean = true): Boolean = withContext(NonCancellable) {
        if (cleared) {
            return@withContext false
        }
        if (!hasChanges()) {
            discard(remove)
            return@withContext false
        }
        clear(remove)
        task.title = if (title.isNullOrBlank()) context.getString(R.string.no_title) else title
        task.dueDate = dueDate!!
        task.priority = priority!!
        task.notes = description
        task.hideUntil = hideUntil!!
        task.recurrence = recurrence
        task.repeatUntil = repeatUntil!!
        task.elapsedSeconds = elapsedSeconds!!
        task.estimatedSeconds = estimatedSeconds!!
        task.ringFlags = getRingFlags()

        applyCalendarChanges()

        val isNew = task.isNew

        if (isNew) {
            taskDao.createNew(task)
        }

        if (isNew || originalList != selectedList) {
            task.parent = 0
            taskMover.move(listOf(task.id), selectedList!!)
        }

        if ((isNew && selectedLocation != null) || originalLocation != selectedLocation) {
            originalLocation?.let { location ->
                if (location.geofence.id > 0) {
                    locationDao.delete(location.geofence)
                    geofenceApi.update(location.place)
                }
            }
            selectedLocation?.let { location ->
                val place = location.place
                val geofence = location.geofence
                geofence.task = task.id
                geofence.place = place.uid
                geofence.id = locationDao.insert(geofence)
                geofenceApi.update(place)
            }
            task.putTransitory(SyncFlags.FORCE_CALDAV_SYNC, true)
            task.modificationDate = currentTimeMillis()
        }

        if ((isNew && selectedTags.isNotEmpty()) || originalTags.toHashSet() != selectedTags.toHashSet()) {
            tagDao.applyTags(task, tagDataDao, selectedTags)
            task.modificationDate = currentTimeMillis()
        }

        for (subtask in newSubtasks) {
            if (Strings.isNullOrEmpty(subtask.title)) {
                continue
            }
            if (!subtask.isCompleted) {
                subtask.completionDate = task.completionDate
            }
            taskDao.createNew(subtask)
            firebase?.addTask("subtasks")
            when (selectedList) {
                is GtasksFilter -> {
                    val googleTask = GoogleTask(subtask.id, (selectedList as GtasksFilter).remoteId)
                    googleTask.parent = task.id
                    googleTask.isMoved = true
                    googleTaskDao.insertAndShift(googleTask, false)
                }
                is CaldavFilter -> {
                    val caldavTask = CaldavTask(subtask.id, (selectedList as CaldavFilter).uuid)
                    subtask.parent = task.id
                    caldavTask.remoteParent = caldavDao.getRemoteIdForTask(task.id)
                    taskDao.save(subtask)
                    caldavDao.insert(subtask, caldavTask, false)
                }
                else -> {
                    subtask.parent = task.id
                    taskDao.save(subtask)
                }
            }
        }

        if (!task.hasStartDate()) {
            selectedAlarms.value = selectedAlarms.value.filterNot { a -> a.type == TYPE_REL_START }
        }
        if (!task.hasDueDate()) {
            selectedAlarms.value = selectedAlarms.value.filterNot { a -> a.type == TYPE_REL_END }
        }

        taskDao.save(task, null)

        if (
            selectedAlarms.value.toHashSet() != originalAlarms.toHashSet() ||
            (isNew && selectedAlarms.value.isNotEmpty())
        ) {
            alarmService.synchronizeAlarms(task.id, selectedAlarms.value.toMutableSet())
            task.putTransitory(SyncFlags.FORCE_CALDAV_SYNC, true)
            task.modificationDate = now()
        }

        if (task.isCompleted != completed!!) {
            taskCompleter.setComplete(task, completed!!)
        }

        if (isNew) {
            val model = task
            taskListEvents.emit(TaskListEvent.TaskCreated(model.uuid))
            model.calendarURI?.takeIf { it.isNotBlank() }?.let {
                taskListEvents.emit(TaskListEvent.CalendarEventCreated(model.title, it))
            }
            mainActivityEvents.emit(MainActivityEvent.RequestRating)
        }
        true
    }

    private suspend fun applyCalendarChanges() {
        if (!permissionChecker.canAccessCalendars()) {
            return
        }
        if (eventUri == null) {
            calendarEventProvider.deleteEvent(task)
        }
        if (!task.hasDueDate()) {
            return
        }
        selectedCalendar?.let {
            try {
                task.calendarURI = gCalHelper.createTaskEvent(task, it)?.toString()
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    private fun getRingFlags() = when {
        ringNonstop == true -> NOTIFY_MODE_NONSTOP
        ringFiveTimes == true -> NOTIFY_MODE_FIVE
        else -> 0
    }

    suspend fun delete() {
        taskDeleter.markDeleted(task)
        discard()
    }

    suspend fun discard(remove: Boolean = true) {
        if (task.isNew) {
            timerPlugin.stopTimer(task)
        }
        clear(remove)
    }

    @MainThread
    suspend fun clear(remove: Boolean = true) {
        if (cleared) {
            return
        }
        cleared = true
        if (remove) {
            mainActivityEvents.emit(MainActivityEvent.ClearTaskEditFragment)
        }
    }

    override fun onCleared() {
        if (!cleared) {
            runBlocking {
                save(remove = false)
            }
        }
    }

    companion object {
        fun String?.stripCarriageReturns(): String? = this?.replace("\\r\\n?".toRegex(), "\n")
    }
}