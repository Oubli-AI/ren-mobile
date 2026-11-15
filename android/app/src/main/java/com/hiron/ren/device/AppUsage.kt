package com.hiron.ren.device

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import com.hiron.ren.browser.AppUse

/**
 * Which apps the user had on screen and for how long, from Android's usage access (Settings › What Ren can use › App
 * usage): the foreground spans in [UsageStatsManager]'s events. Ren itself and the home screen are left out.
 */
class AppUsage(private val context: Context) {
    /** Whether the user gave Ren usage access. */
    fun allowed(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** The apps used between [since] and [until], longest first, each for at least a minute; empty without access. */
    fun between(since: Long, until: Long): List<AppUse> {
        if (!allowed()) return emptyList()
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val skip = setOfNotNull(context.packageName, home())
        val events = manager.queryEvents(since, until)
        val event = UsageEvents.Event()
        val opened = HashMap<String, Long>()
        val spent = HashMap<String, Long>()
        while (events.getNextEvent(event)) {
            val pkg = event.packageName ?: continue
            if (pkg in skip) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> opened.putIfAbsent(pkg, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    opened.remove(pkg)?.let { start -> spent[pkg] = (spent[pkg] ?: 0L) + (event.timeStamp - start) }
            }
        }
        // An app still on screen when the window ends counts up to its end.
        opened.forEach { (pkg, start) -> spent[pkg] = (spent[pkg] ?: 0L) + (until - start) }
        return spent.mapNotNull { (pkg, ms) -> (ms / 60_000L).toInt().takeIf { it >= 1 }?.let { AppUse(label(pkg), it) } }
            .sortedByDescending { it.minutes }
    }

    private fun label(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun home(): String? = context.packageManager
        .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
}
