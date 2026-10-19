package com.mxfia19.twitchunblock.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.mxfia19.twitchunblock.R
import com.mxfia19.twitchunblock.ui.MainActivity

/** Service des téléchargements : tourne au premier plan (notification de
 *  progression) tant qu'une VOD se télécharge, même app fermée. */
@OptIn(UnstableApi::class)
class VodDownloadService : DownloadService(
    NOTIFICATION_ID,
    DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.dl_channel,
    0,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = Downloads.manager

    // Pas de planificateur : les téléchargements interrompus reprennent à la
    // prochaine ouverture de l'app (Downloads.resumePending).
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return helper.buildProgressNotification(this, android.R.drawable.stat_sys_download, open, null,
                                                downloads, notMetRequirements)
    }

    companion object {
        private const val NOTIFICATION_ID = 4242
        private const val CHANNEL_ID = "downloads"
    }
}
