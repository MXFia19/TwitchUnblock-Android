package com.mxfia19.twitchunblock.core

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Source « Mon proxy » (série 7) : un proxy HTTP(S) choisi par l'utilisateur
 * (hôte:port, identifiant et mot de passe facultatifs ; le mot de passe est
 * chiffré par le Keystore, comme les jetons du compte). Quand cette source
 * sert, le jeton (GQL), la liste maîtresse (usher) et les playlists de la
 * vidéo passent par lui (Proxy et proxyAuthenticator d'OkHttp) ; les
 * segments restent en direct. Proposée dans l'ordre des sources seulement
 * quand un proxy est réglé, comme Ma source. Propre à l'appareil : ni dans
 * la sauvegarde ni synchronisé, et son adresse n'est jamais écrite au
 * journal (il part avec les signalements).
 */
object MyProxy {
    private const val PASSWORD_KEY = "my_proxy_password"
    /** Playlists retenues au plus (une par qualité, 4 tuiles du multi-live…). */
    private const val MAX_ROUTES = 120

    /** « hôte:port » (« [IPv6]:port »), vide sans proxy. */
    val address = stringPref("my_proxy_address", "")
    /** Identifiant, vide sans (le mot de passe ne sert alors pas). */
    val user = stringPref("my_proxy_user", "")

    @Volatile
    private var pass: String? = null
    private var cachedKey: String? = null
    private var cachedClient: OkHttpClient? = null
    /** Adresses exactes des playlists à lire par le proxy, les plus anciennes d'abord. */
    private val routed = LinkedHashSet<String>()

    val configured: Boolean get() = parse(address.value) != null

    /** Mot de passe en clair pour l'app, vide sans. */
    fun password(): String = pass ?: (Secrets.read(AppState.prefs, PASSWORD_KEY) ?: "").also { pass = it }

    /**
     * (hôte, port) d'une adresse « hôte:port » : un nom de domaine, une IPv4
     * ou une IPv6 entre crochets, et un port de 1 à 65535 ; « http:// » ou
     * « https:// » devant sont tolérés. null si elle ne va pas.
     */
    fun parse(raw: String): Pair<String, Int>? {
        var v = raw.trim()
        if (v.isEmpty() || v.length > 200) return null
        for (scheme in listOf("http://", "https://")) {
            if (v.startsWith(scheme, ignoreCase = true)) v = v.substring(scheme.length)
        }
        v = v.trimEnd('/')
        val host: String
        val port: String
        if (v.startsWith("[")) {
            val end = v.indexOf(']')
            if (end < 2 || v.getOrNull(end + 1) != ':') return null
            host = v.substring(1, end)
            port = v.substring(end + 2)
            if (!host.contains(':') ||
                !host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        } else {
            val i = v.lastIndexOf(':')
            if (i <= 0) return null
            host = v.substring(0, i)
            port = v.substring(i + 1)
            if (host.length > 253 || host.startsWith('.') || host.endsWith('.') || host.startsWith('-') || host.contains("..")) return null
            if (!host.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.' }) return null
        }
        if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return null
        val n = port.toInt()
        if (n !in 1..65535) return null
        return Pair(host.lowercase(), n)
    }

    private fun format(hp: Pair<String, Int>): String =
        if (hp.first.contains(':')) "[${hp.first}]:${hp.second}" else "${hp.first}:${hp.second}"

    /** Réglages enregistrés si l'adresse est valable (sinon faux, les anciens
     *  restent) ; adresse vide : proxy retiré, identifiants effacés. */
    fun set(rawAddress: String, rawUser: String, rawPassword: String): Boolean {
        val trimmed = rawAddress.trim()
        val was = address.value
        if (trimmed.isEmpty()) {
            address.set("")
            user.set("")
            savePassword("")
        } else {
            val hp = parse(trimmed) ?: return false
            address.set(format(hp))
            user.set(rawUser.trim().take(200))
            savePassword(if (user.value.isEmpty()) "" else rawPassword.take(200))
        }
        synchronized(this) {
            cachedClient = null
            cachedKey = null
        }
        // Ordre des sources rangé : Mon proxy y entre en tête, ou en sort.
        if (address.value != was) LiveSources.onMyProxyChanged()
        return true
    }

    private fun savePassword(value: String) {
        pass = value
        val editor = AppState.prefs.edit()
        if (value.isEmpty()) editor.remove(PASSWORD_KEY) else Secrets.put(editor, PASSWORD_KEY, value)
        editor.apply()
    }

    /** Client OkHttp qui passe par le proxy réglé (identifiants envoyés quand
     *  le proxy les demande, une fois) ; null sans proxy valable. */
    @Synchronized
    fun client(): OkHttpClient? {
        val hp = parse(address.value) ?: return null
        val name = user.value
        val secret = password()
        val key = "${hp.first}|${hp.second}|$name|${secret.hashCode()}"
        cachedClient?.let { if (key == cachedKey) return it }
        val builder = Http.client.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(hp.first, hp.second)))
            .callTimeout(15, TimeUnit.SECONDS)
        if (name.isNotEmpty()) {
            val credential = Credentials.basic(name, secret)
            builder.proxyAuthenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    // Déjà refusés avec ces identifiants : on n'insiste pas.
                    if (response.request.header("Proxy-Authorization") != null) return null
                    return response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            })
        }
        return builder.build().also {
            cachedClient = it
            cachedKey = key
        }
    }

    /** Playlists de la vidéo d'un direct servi par Mon proxy : le lecteur les
     *  lira par lui (fetchPlaylist). */
    fun route(urls: Collection<String>) {
        synchronized(routed) {
            for (u in urls) {
                routed.remove(u)
                routed.add(u)
            }
            while (routed.size > MAX_ROUTES) routed.remove(routed.first())
        }
    }

    private fun routes(url: String): Boolean = synchronized(routed) { url in routed }

    /**
     * Playlist lue par le proxy (fil du lecteur, PlaylistRewritingDataSource) ;
     * null si elle n'est pas pour lui (lue en direct, comme avant) ou si le
     * proxy a été retiré entre-temps. Échec : IOException, le lecteur réessaie
     * comme après une coupure.
     */
    @Throws(IOException::class)
    fun fetchPlaylist(url: String): ByteArray? {
        if (!routes(url)) return null
        val c = client() ?: return null
        val request = try {
            Request.Builder().url(url).header("User-Agent", Config.USER_AGENT).build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        return c.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Mon proxy : HTTP ${r.code}")
            r.body?.bytes() ?: ByteArray(0)
        }
    }
}
