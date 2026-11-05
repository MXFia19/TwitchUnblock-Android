package com.mxfia19.twitchunblock.core

/**
 * Worker personnel (Réglages → Worker personnel) : vérifié avant d'être
 * gardé, puis essayé en premier pour la lecture (Workers, lookupBases).
 * « Tout passer par ce Worker » y envoie aussi le reste : synchro de
 * l'historique et des réglages (avec le jeton Twitch), signalements,
 * annonces, sondages et comptage. Comme APIConfig (iOS).
 */
object CustomWorker {
    /** « Tout passer par ce Worker » (coupé quand le Worker est retiré). */
    val all = boolPref("custom_worker_all", false)

    /** Worker des routes à base D1 : le personnel si « tout » est actif,
     *  sinon le principal. Les notifications ntfy restent au principal : seul
     *  lui passe par le relais (ntfy.sh refuse les Workers). */
    val base: String
        get() = AppState.customWorker?.takeIf { all.value } ?: Config.API_URL

    /** Résultat de la vérification, avec le message affiché sous le champ. */
    enum class Check(val messageKey: String) {
        OK("expert_ok"),
        /** Pas de réponse : adresse, réseau, certificat, délai. */
        UNREACHABLE("expert_unreachable"),
        /** Quota atteint (Cloudflare : 429, « error code: 1027 »). */
        QUOTA("expert_quota"),
        /** Il répond, mais pas comme un Worker TwitchUnblock. */
        NOT_A_WORKER("expert_bad"),
    }

    /** Un Worker TwitchUnblock répond à un nom de chaîne vide par une erreur
     *  JSON (400) : c'est ce qu'on vérifie avant de s'en servir. */
    suspend fun check(base: String): Check {
        val r = Http.get("$base/api/get-live?name=", timeout = 10) ?: return Check.UNREACHABLE
        if (r.code == 429) return Check.QUOTA
        if (r.code != 400 && r.code != 200) return Check.NOT_A_WORKER
        val json = parseJson(r.body) ?: return Check.NOT_A_WORKER
        return if (json.has("error") || json.has("links")) Check.OK else Check.NOT_A_WORKER
    }

    /** Le Worker a-t-il sa base D1 (sauvegarde, retours, annonces) ? Sans
     *  elle, /api/sync/get répond 500 avant de lire le jeton ; avec, une
     *  demande sans jeton est refusée. null : pas de réponse claire. */
    suspend fun hasDatabase(base: String): Boolean? {
        val code = Http.get("$base/api/sync/get?userId=0", timeout = 10)?.code ?: return null
        if (code == 500) return false
        return if (code == 400 || code == 401 || code == 403) true else null
    }
}
