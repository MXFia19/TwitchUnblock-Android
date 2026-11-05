package com.mxfia19.twitchunblock.feedback

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Réponses aux signalements suivies en continu : relues chaque minute quand
 * l'app est à l'écran, et toutes les 30 min en arrière-plan (WorkManager)
 * tant qu'un signalement attend encore quelque chose. Une réponse arrive en
 * bandeau, ou en notification qui ouvre « Mes signalements » (Feedback).
 * Comme startForegroundChecks et le réveil de LiveNotifier (iOS).
 */
object FeedbackWatch {
    private const val WORK = "feedback-check"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    /** Dernier état demandé à WorkManager pendant ce lancement. */
    private var scheduled: Boolean? = null

    /** App au premier plan (InAppNotices) : relue en arrivant (au plus toutes
     *  les 3 min), puis chaque minute. Sans signalement suivi, aucune requête. */
    fun foregroundChanged(on: Boolean) {
        loop?.cancel()
        loop = null
        if (!on) return
        loop = scope.launch {
            Feedback.refresh()
            while (true) {
                delay(60_000)
                Feedback.refresh(minInterval = 50_000L)
            }
        }
    }

    /** Relecture en arrière-plan seulement s'il reste des signalements suivis. */
    fun update(context: Context, needed: Boolean) {
        if (scheduled == needed) return
        scheduled = needed
        try {
            val wm = WorkManager.getInstance(context)
            if (needed) {
                val request = PeriodicWorkRequestBuilder<FeedbackCheckWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                wm.cancelUniqueWork(WORK)
            }
        } catch (e: IllegalStateException) {
            // WorkManager pas encore prêt : redemandé au prochain enregistrement.
            scheduled = null
        }
    }
}

/** Relecture des signalements en arrière-plan. */
class FeedbackCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        Feedback.refresh(minInterval = 60_000L)
        Result.success()
    }
}
