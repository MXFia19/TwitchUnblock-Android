package com.mxfia19.twitchunblock.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mxfia19.twitchunblock.core.Format
import com.mxfia19.twitchunblock.core.Gql
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.obj
import com.mxfia19.twitchunblock.core.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Issue d'une demande de clip. */
sealed class ClipOutcome {
    data class Created(val url: String) : ClipOutcome()
    /** Session web absente, ou refusée par Twitch : la connecter. */
    object NeedsSession : ClipOutcome()
    /** La chaîne n'est plus en direct. */
    object Offline : ClipOutcome()
    /** Clips coupés sur cette chaîne, ou réservés à certains spectateurs. */
    object Disabled : ClipOutcome()
    /** Trop de clips d'affilée. */
    object RateLimited : ClipOutcome()
    /** Pas de réponse lisible de Twitch (réseau, jeton d'intégrité). */
    object Network : ClipOutcome()
    /** Autre refus : son code, ou les messages de Twitch. */
    data class Refused(val code: String) : ClipOutcome()
}

/**
 * Créer un clip du direct. Helix ne le fait qu'avec la portée « clips:edit »,
 * que la connexion de l'app ne demande pas : on passe par la session web,
 * comme les points et les prédictions — mutation GQL « createClip » signée du
 * jeton d'intégrité. Twitch ne publie pas de requête enregistrée pour elle :
 * elle part écrite, puis sous une forme réduite si Twitch ne reconnaît pas un
 * de ses champs (API web non documentée). Gardé ici et pas dans la fenêtre du
 * lecteur : fermée pendant la création, le clip part quand même et son issue
 * est notée au journal. Comme ClipCreator (iOS). Fil principal.
 */
object ClipCreator {
    sealed class State {
        object Idle : State()
        object Working : State()
        data class Finished(val outcome: ClipOutcome) : State()
    }

    /** Ce que montre la fenêtre du clip ; Idle : fermée. */
    var state by mutableStateOf<State>(State.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var attempt = 0
    /** Chaîne et instant demandés : gardés pour « Réessayer » et pour la
     *  reprise après la connexion de la session web (même instant). */
    private var login = ""
    private var moment = 0L

    /** Nouveau clip du direct de `login`. `moment` : heure (ms) de l'image
     *  affichée (horloge de l'appareil moins le retard du direct). */
    fun start(login: String, moment: Long) {
        this.login = login.lowercase()
        this.moment = moment
        begin()
    }

    /** Même chaîne, même instant : après une erreur, ou la session connectée. */
    fun retry() {
        if (login.isNotEmpty()) begin()
    }

    /** Fenêtre fermée ; une création en cours continue (issue au journal). */
    fun close() {
        state = State.Idle
    }

    private fun begin() {
        job?.cancel()
        val token = WebSession.token?.takeIf { it.isNotEmpty() && !WebSession.expired }
        if (token == null) {
            Logger.info("CLIP", "Session web absente : connexion proposée", "@$login")
            state = State.Finished(ClipOutcome.NeedsSession)
            return
        }
        state = State.Working
        val id = ++attempt
        val login = this.login
        val moment = this.moment
        job = scope.launch {
            var outcome = create(login, moment, token)
            // Rien de lisible et session refusée (401) : la connexion est
            // proposée au lieu d'un « réessaie ».
            if (outcome == ClipOutcome.Network && WebSession.expired) outcome = ClipOutcome.NeedsSession
            if (id == attempt && state == State.Working) state = State.Finished(outcome)
        }
    }

    /** Crée le clip ; chaque issue est notée au journal. */
    private suspend fun create(login: String, moment: Long, token: String): ClipOutcome {
        // Chaîne et diffusion en cours : l'instant se compte depuis son début.
        val d = Gql.request("query(\$l: String!) { user(login: \$l) { id stream { id createdAt } } }",
                            Gql.vars("l" to login))
        val user = d?.obj("user")
        val channelId = user?.str("id")
        if (user == null || channelId.isNullOrEmpty()) {
            Logger.warn("CLIP", "Direct illisible : pas de clip", "@$login")
            return ClipOutcome.Network
        }
        val stream = user.obj("stream")
        val broadcastId = stream?.str("id")
        if (stream == null || broadcastId.isNullOrEmpty()) {
            Logger.warn("CLIP", "Chaîne hors ligne : pas de clip", "@$login")
            return ClipOutcome.Offline
        }
        val started = Format.parseDate(stream.str("createdAt"))
        if (started == null) {
            Logger.warn("CLIP", "Début du direct inconnu : pas de clip", "@$login")
            return ClipOutcome.Refused("createdAt")
        }
        val offset = maxOf(0L, Math.round((moment - started) / 1000.0))
        Logger.info("CLIP", "Création d'un clip…", "@$login · $offset s")
        // Forme complète d'abord (diffusion visée, code du refus), puis réduite
        // si Twitch ne reconnaît pas un de ses champs.
        val full = JSONObject().put("broadcasterID", channelId).put("broadcastID", broadcastId)
            .put("offsetSeconds", offset)
        send(FULL, full, token)?.let { return it }
        Logger.warn("CLIP", "Requête de clip non reconnue : forme réduite")
        val bare = JSONObject().put("broadcasterID", channelId).put("offsetSeconds", offset)
        send(BARE, bare, token)?.let { return it }
        Logger.error("CLIP", "Requête de clip refusée par Twitch", "forme réduite non reconnue")
        return ClipOutcome.Refused("schema")
    }

    private const val FULL = "mutation CreateClip(\$input: CreateClipInput!) { " +
        "createClip(input: \$input) { clip { slug } error { code } } }"
    private const val BARE = "mutation CreateClip(\$input: CreateClipInput!) { " +
        "createClip(input: \$input) { clip { slug } } }"

    /** Une tentative. null : requête refusée avant d'être exécutée (champ ou
     *  argument que Twitch ne connaît pas). */
    private suspend fun send(query: String, input: JSONObject, token: String): ClipOutcome? {
        val json = WebGql.mutation(WebGql.written(query, JSONObject().put("input", input)), token, "CreateClip")
        if (json == null) {
            Logger.warn("CLIP", "Pas de réponse de Twitch", "réseau ou jeton d'intégrité")
            return ClipOutcome.Network
        }
        val payload = json.obj("data")?.obj("createClip")
        val slug = payload?.obj("clip")?.str("slug")
        if (!slug.isNullOrEmpty()) {
            val url = "https://clips.twitch.tv/$slug"
            Logger.success("CLIP", "Clip créé", url)
            return ClipOutcome.Created(url)
        }
        val code = payload?.obj("error")?.str("code") ?: ""
        val messages = WebGql.errors(json)
        if (payload == null && code.isEmpty() && isSchemaError(messages)) return null
        Logger.warn("CLIP", "Clip refusé", code.ifEmpty { messages.ifEmpty { "réponse inattendue" } })
        return outcome(code, messages)
    }

    /** Erreur de validation GraphQL : la requête n'a pas été exécutée. */
    private fun isSchemaError(messages: String): Boolean {
        val m = messages.lowercase()
        return listOf("cannot query field", "unknown field", "unknown argument", "is not defined",
                      "invalid value", "unknown type", "expected type").any { m.contains(it) }
    }

    /** Refus de Twitch rangé par cause (codes de l'API web non documentés :
     *  on lit des mots, pas une liste). */
    private fun outcome(code: String, messages: String): ClipOutcome {
        val text = "$code $messages".uppercase()
        fun has(vararg words: String) = words.any { text.contains(it) }
        return when {
            has("NOT_LIVE", "NOT LIVE", "OFFLINE", "NOT_BROADCASTING", "NO_BROADCAST", "BROADCAST_NOT_FOUND") ->
                ClipOutcome.Offline
            has("RATE", "LIMIT", "THROTTL", "TOO_MANY", "TOO MANY") -> ClipOutcome.RateLimited
            has("DISABLED", "RESTRICT", "FORBIDDEN", "NOT_ALLOWED", "BANNED", "PERMISSION", "FOLLOW", "SUBSCRI",
                "INELIGIBLE") -> ClipOutcome.Disabled
            else -> ClipOutcome.Refused(code.ifEmpty { messages.ifEmpty { "?" } })
        }
    }
}
