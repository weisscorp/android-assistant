package local.voice

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.ZoneId

/** CalendarProvider is part of Android; no Google account or Google API is used. */
object ClockCalendar {
    data class Choice(val id: Long, val name: String)
    fun permitted(context: Context) = listOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR).all {
        context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED
    }
    fun calendars(context: Context): List<Choice> {
        if(!permitted(context)) return emptyList()
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,arrayOf("_id","calendar_displayName"),
            "calendar_access_level>=? AND visible=1",arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),"calendar_displayName ASC")?.use { c ->
            buildList { while(c.moveToNext()) add(Choice(c.getLong(0),c.getString(1).orEmpty())) }
        }.orEmpty()
    }
    fun selected(context: Context): Choice? {
        val id=context.getSharedPreferences("settings",0).getLong("clock_calendar",-1)
        val choices=calendars(context)
        return choices.firstOrNull { it.id==id } ?: choices.singleOrNull()
    }
    fun select(context: Context, id: Long) {
        check(calendars(context).any { it.id==id }) { "Календарь больше недоступен." }
        check(context.getSharedPreferences("settings",0).edit().putLong("clock_calendar",id).commit())
    }
    fun createLocal(context: Context): Choice {
        check(permitted(context)) { "Нужен доступ к календарю." }
        val existing=context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,arrayOf("_id","calendar_displayName"),"account_name=? AND account_type=?",arrayOf(context.packageName,CalendarContract.ACCOUNT_TYPE_LOCAL),null)?.use { c -> if(c.moveToFirst()) Choice(c.getLong(0),c.getString(1)) else null }
        if(existing != null) { select(context,existing.id); return existing }
        val uri=CalendarContract.Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME,context.packageName).appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val result=context.contentResolver.insert(uri,ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME,context.packageName); put(CalendarContract.Calendars.ACCOUNT_TYPE,CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME,"assistant_reminders"); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,"Ассистент")
            put(CalendarContract.Calendars.OWNER_ACCOUNT,context.packageName); put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.CALENDAR_COLOR,0xff73d8ff.toInt()); put(CalendarContract.Calendars.CALENDAR_TIME_ZONE,ZoneId.systemDefault().id)
            put(CalendarContract.Calendars.VISIBLE,1); put(CalendarContract.Calendars.SYNC_EVENTS,1)
        }) ?: error("Android не предоставил календарь.")
        val choice=Choice(ContentUris.parseId(result),"Ассистент")
        select(context,choice.id)
        return choice
    }
    fun add(context: Context, calendar: Choice, title: String, at: Instant): Long {
        check(permitted(context) && calendars(context).any { it.id==calendar.id }) { "Доступ к выбранному календарю изменился." }
        val uri=context.contentResolver.insert(CalendarContract.Events.CONTENT_URI,ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID,calendar.id); put(CalendarContract.Events.TITLE,title)
            put(CalendarContract.Events.DTSTART,at.toEpochMilli()); put(CalendarContract.Events.DTEND,at.plusSeconds(60).toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE,ZoneId.systemDefault().id)
            put(CalendarContract.Events.DESCRIPTION,"Напоминание ассистента. Сигнал подаёт приложение Ассистент в указанное время.")
            // Our exact alarm supplies the alert. Avoid duplicate sound from calendar apps.
            put(CalendarContract.Events.HAS_ALARM,0)
        }) ?: error("Не удалось записать событие в календарь.")
        val id=ContentUris.parseId(uri)
        try {
            val verified=context.contentResolver.query(uri,arrayOf(CalendarContract.Events.TITLE,CalendarContract.Events.DTSTART),null,null,null)?.use { c ->
                c.moveToFirst() && c.getString(0)==title && c.getLong(1)==at.toEpochMilli()
            } == true
            check(verified) { "Не удалось проверить запись календаря." }
        } catch(e: Exception) { runCatching { context.contentResolver.delete(uri,null,null) }; throw e }
        return id
    }
    fun delete(context: Context, id: Long) {
        check(permitted(context)) { "Разрешите доступ к календарю, чтобы удалить напоминание." }
        // IDs come only from our private clocks database, never from a model or notification.
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),null,null)
    }
}
