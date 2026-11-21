package com.mxfia19.twitchunblock.core

/**
 * Réserve du quota du jour du Worker (worker.js, dépôt du site), comme
 * l'iPhone (QuotaPause.swift). Le Worker gratuit a 100 000 requêtes par
 * jour, et Cloudflare compte même celles qu'il refuse. Passé 80 %, il le dit
 * sur le comptage, les annonces et les sondages (en-tête X-TU-Quota: reserve,
 * Retry-After jusqu'à minuit UTC) : ces envois automatiques attendent alors,
 * pour laisser le reste du quota à la lecture et aux sauvegardes. Le relais
 * des minutes des points (/api/spade) répond 429 avec le code
 * « quota_reserve » : Spade laisse ce Worker jusqu'à l'heure dite. Passé
 * 95 %, la lecture part au secours d'elle-même (le Worker répond « 1027 »,
 * comme au quota atteint ; Workers.kt).
 */
object QuotaPause {
    private const val KEY = "quota_pause_until"

    /** Envois automatiques en pause ? */
    val active: Boolean get() = AppState.prefs.getLong(KEY, 0L) > System.currentTimeMillis()

    /** Lit l'avis de la réserve sur une réponse du Worker. */
    fun note(r: HttpResult?) {
        if (r?.quota != "reserve") return
        val until = System.currentTimeMillis() + waitMs(r.retryAfter)
        if (until > AppState.prefs.getLong(KEY, 0L)) AppState.prefs.edit().putLong(KEY, until).apply()
    }

    /** Attente d'après un Retry-After (secondes) : une heure s'il manque, un
     *  jour au plus. */
    fun waitMs(retryAfter: String?): Long {
        val s = retryAfter?.trim()?.toDoubleOrNull()
        return if (s == null || s <= 0) 3_600_000L else (minOf(s, 86_400.0) * 1000).toLong()
    }
}
