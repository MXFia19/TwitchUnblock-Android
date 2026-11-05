package com.mxfia19.twitchunblock.download

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mxfia19.twitchunblock.R
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.ui.MainActivity

/**
 * Téléchargements en attente du réseau (ou du Wi-Fi), app fermée (série 6) :
 * le gestionnaire de Media3 n'a pas de planificateur (il l'ignore de toute
 * façon depuis Android 12), et son service ne doit pas rester au premier plan
 * pendant l'attente (limite du type dataSync sur Android 15). WorkManager
 * guette donc le retour du réseau : avant Android 12, il relance lui-même le
 * service (premier plan permis depuis l'arrière-plan, réseau là : il
 * télécharge aussitôt) ; depuis, une notification propose de reprendre, et
 * l'ouverture de l'app relance le service (Downloads.resumePending).
 */
object DownloadWaiter {
    private const val WORK = "tu-download-wait"
    private const val NOTIFICATION_ID = 4243

    /** Attente commencée (`waiting`) ou finie ; `wifiOnly` : réseau non facturé exigé. */
    fun update(context: Context, waiting: Boolean, wifiOnly: Boolean) {
        try {
            val wm = WorkManager.getInstance(context)
            if (!waiting) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val request = OneTimeWorkRequestBuilder<DownloadWaitWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
        } catch (e: Exception) {
            Logger.warn("DOWNLOAD", "Attente du réseau non programmée", e.message)
        }
    }

    /** App au premier plan : son gestionnaire repart seul au retour du réseau. */
    private fun appVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    @OptIn(UnstableApi::class)
    fun onNetworkBack(context: Context) {
        val pending = Downloads.items.any { it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING }
        if (!pending || appVisible()) return
        Downloads.allowResume()
        if (Build.VERSION.SDK_INT < 31) {
            try {
                DownloadService.sendResumeDownloads(context, VodDownloadService::class.java, true)
                Logger.info("DOWNLOAD", "Réseau revenu : téléchargements repris", "app fermée")
                return
            } catch (e: Exception) {
                Logger.warn("DOWNLOAD", "Reprise impossible en arrière-plan", e.message)
            }
        }
        notifyBack(context)
    }

    /** « Le réseau est revenu : touche pour reprendre » ; l'ouverture de l'app relance le service. */
    private fun notifyBack(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = "downloads"
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channel) == null) {
                nm.createNotificationChannel(NotificationChannel(channel, context.getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(AppState.t("dl_section"))
                .setContentText(AppState.t("m6_dl_resume_notif"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(AppState.t("m6_dl_resume_notif")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
            Logger.info("DOWNLOAD", "Réseau revenu : reprise proposée", "notification")
        } catch (e: Exception) {
            Logger.warn("DOWNLOAD", "Notification de reprise impossible", e.message)
        }
    }
}

/** Lancé par WorkManager quand le réseau attendu revient (DownloadWaiter). */
class DownloadWaitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DownloadWaiter.onNetworkBack(applicationContext)
        return Result.success()
    }
}
