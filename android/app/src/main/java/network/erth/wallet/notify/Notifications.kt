package network.erth.wallet.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import network.erth.wallet.R
import network.erth.wallet.chain.EarthRest
import network.erth.wallet.ui.MainActivity
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Local notifications: the daily ANML claim opening, and a new proposal open
 * for voting. Nothing is sent anywhere to produce them.
 *
 * The claim times are worked out here from the wallet's own record and set as
 * alarms; the proposal check reads the public proposal list from the chosen
 * node and nothing about the wallet. Both only tell: tapping opens the app,
 * and the claim or vote is still the user's own confirmed tx.
 *
 * AlarmManager and JobScheduler rather than WorkManager: both are the
 * platform's, and WorkManager would bring a database for two small schedules.
 */
object Notifications {

    private const val CHANNEL = "earth"
    private const val PREFS = "notifications"
    private const val KEY_ANML_TIMES = "anml_times"
    private const val KEY_NOTIFIED = "proposals_notified"
    private const val KEY_ASKED = "permission_asked"
    private const val KEY_ANML_ON = "anml_enabled"
    private const val KEY_PROPOSALS_ON = "proposals_enabled"

    private const val ANML_DAYS = 7
    private const val ANML_ID = 1
    private const val PROPOSAL_ID_BASE = 1000
    private const val PROPOSAL_JOB = 1
    private const val PROPOSAL_PERIOD_MS = 6 * 60 * 60 * 1000L
    private const val SECONDS_PER_DAY = 86_400L

    /** The extra a notification's tap carries; read by MainActivity. */
    const val EXTRA_OPEN = "network.erth.wallet.open"
    const val OPEN_GOVERN = "govern"

    /** Where a tapped notification asked to go, until the shell takes it. */
    val pendingOpen = MutableStateFlow<String?>(null)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Earth", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Whether to ask for POST_NOTIFICATIONS now: Android 13+, not granted, never asked. Marks it asked. */
    fun shouldAsk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        val p = prefs(ctx)
        if (p.getBoolean(KEY_ASKED, false)) return false
        p.edit().putBoolean(KEY_ASKED, true).apply()
        return true
    }

    fun anmlEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ANML_ON, true)

    fun proposalsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PROPOSALS_ON, true)

    /**
     * Off cancels the alarms; on sets them again from the wallet's record as
     * last synced. Blocking (it opens the private wallet): call off the main
     * thread. A locked wallet sets nothing, and the next sync does.
     */
    fun setAnmlEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ANML_ON, on).apply()
        if (!on) {
            cancelAnml(ctx)
            return
        }
        val opensAt = runCatching { network.erth.wallet.privacy.PrivacySession.wallet(ctx).claimOpensAt() }.getOrNull() ?: return
        syncAnml(ctx, opensAt)
    }

    fun setProposalsEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PROPOSALS_ON, on).apply()
    }

    /** Whether the system lets this app post at all: the Android 13 permission and the app's notification switch. */
    fun systemAllowed(ctx: Context): Boolean = canPost(ctx)

    private fun canPost(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    @android.annotation.SuppressLint("MissingPermission") // canPost checks it
    private fun post(ctx: Context, id: Int, title: String, body: String, open: String?): Boolean {
        if (!canPost(ctx)) return false
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { open?.let { putExtra(EXTRA_OPEN, it) } }
        val tap = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_public)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        return runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }.isSuccess
    }

    // --- the ANML claim ---

    /**
     * Sets the next week of claim openings from PrivacyWallet.claimOpensAt, or
     * cancels them for null (no live registration). Only called after a sync
     * that succeeded with the private wallet open: a locked or offline wallet
     * says nothing about the registration, so it leaves the alarms alone.
     */
    fun syncAnml(ctx: Context, claimOpensAt: Long?) {
        if (claimOpensAt == null || !anmlEnabled(ctx)) {
            cancelAnml(ctx)
            return
        }
        // 0 is open now: the Claim button says so, the first alarm is tomorrow's.
        val now = System.currentTimeMillis() / 1000
        val first = if (claimOpensAt == 0L) (now / SECONDS_PER_DAY + 1) * SECONDS_PER_DAY else claimOpensAt
        val times = (0 until ANML_DAYS).map { first + it * SECONDS_PER_DAY }
        prefs(ctx).edit().putString(KEY_ANML_TIMES, times.joinToString(",")).apply()
        setAnmlAlarms(ctx, times)
    }

    private fun cancelAnml(ctx: Context) {
        prefs(ctx).edit().remove(KEY_ANML_TIMES).apply()
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        (0 until ANML_DAYS).forEach { am.cancel(anmlIntent(ctx, it)) }
    }

    /** Alarms do not survive a reboot; the saved times do. */
    fun restoreAnml(ctx: Context) {
        val times = prefs(ctx).getString(KEY_ANML_TIMES, null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return
        setAnmlAlarms(ctx, times)
    }

    private fun setAnmlAlarms(ctx: Context, times: List<Long>) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis() / 1000
        times.forEachIndexed { slot, t ->
            val pi = anmlIntent(ctx, slot)
            // Inexact: needs no exact-alarm permission, and a few minutes late is fine for a daily claim.
            if (t > now) am.set(AlarmManager.RTC, t * 1000, pi) else am.cancel(pi)
        }
    }

    private fun anmlIntent(ctx: Context, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, slot, Intent(ctx, AnmlAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal fun postAnml(ctx: Context) {
        post(ctx, ANML_ID, "ANML ready", "Today's ANML is ready to claim.", null)
    }

    // --- new proposals ---

    /**
     * Posts one notification per proposal in its voting period not posted
     * before. Blocking: call off the main thread. An id is remembered only once
     * its notification is posted, so one found while the system blocks them is
     * told once they are allowed. With the user's switch off each is remembered
     * unposted instead: turning it back on must not announce old ones as new.
     */
    @Synchronized
    fun checkProposals(ctx: Context) {
        val (code, body) = EarthRest.get("/cosmos/gov/v1/proposals?proposal_status=PROPOSAL_STATUS_VOTING_PERIOD&pagination.limit=50")
        if (code !in 200..299) return
        val arr = JSONObject(body).optJSONArray("proposals") ?: return
        val p = prefs(ctx)
        val notified = p.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty().toMutableSet()
        val on = proposalsEnabled(ctx)
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("status") != "PROPOSAL_STATUS_VOTING_PERIOD") continue
            val id = o.optString("id").toLongOrNull() ?: continue
            if (id.toString() in notified) continue
            val title = o.optString("title").ifBlank { "Proposal $id" }
            val nid = PROPOSAL_ID_BASE + (id % 1_000_000).toInt()
            if (!on || post(ctx, nid, "New proposal", "#$id $title is open for voting.", OPEN_GOVERN)) {
                notified.add(id.toString())
                changed = true
            }
        }
        if (changed) p.edit().putStringSet(KEY_NOTIFIED, notified).apply()
    }

    /** The background check, every ~6 hours on any network. Kept as is when already scheduled, so a launch does not restart its period. */
    fun scheduleProposalChecks(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.allPendingJobs.any { it.id == PROPOSAL_JOB }) return
        js.schedule(
            JobInfo.Builder(PROPOSAL_JOB, ComponentName(ctx, ProposalCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PROPOSAL_PERIOD_MS)
                .setPersisted(true)
                .build(),
        )
    }
}

class AnmlAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.postAnml(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Notifications.restoreAnml(context)
    }
}

class ProposalCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(isDaemon = true, name = "proposal-check") {
            runCatching { Notifications.checkProposals(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
