package com.mxfia19.twitchunblock.core

import android.util.Log

/** Journal de l'app : logcat, et les 1 000 dernières lignes en mémoire
 *  (écran Journal, signalements). */
object Logger {
    enum class Level(val mark: String) { DEBUG("·"), INFO("ℹ"), SUCCESS("✓"), WARN("⚠"), ERROR("✕") }

    class Entry(val time: Long, val level: Level, val tag: String, val message: String, val detail: String?)

    private val entries = ArrayDeque<Entry>()
    private const val MAX = 1000

    @Synchronized
    fun add(level: Level, tag: String, message: String, detail: String? = null) {
        entries.addLast(Entry(System.currentTimeMillis(), level, tag, message, detail))
        while (entries.size > MAX) entries.removeFirst()
        val text = if (detail.isNullOrEmpty()) message else "$message — $detail"
        val priority = when (level) {
            Level.DEBUG -> Log.DEBUG
            Level.INFO, Level.SUCCESS -> Log.INFO
            Level.WARN -> Log.WARN
            Level.ERROR -> Log.ERROR
        }
        Log.println(priority, "TU/$tag", text)
    }

    fun debug(tag: String, message: String, detail: String? = null) = add(Level.DEBUG, tag, message, detail)
    fun info(tag: String, message: String, detail: String? = null) = add(Level.INFO, tag, message, detail)
    fun success(tag: String, message: String, detail: String? = null) = add(Level.SUCCESS, tag, message, detail)
    fun warn(tag: String, message: String, detail: String? = null) = add(Level.WARN, tag, message, detail)
    fun error(tag: String, message: String, detail: String? = null) = add(Level.ERROR, tag, message, detail)

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
