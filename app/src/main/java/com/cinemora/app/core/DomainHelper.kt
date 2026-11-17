package com.cinemora.app.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Port of the raghav extensions' FirebaseDomainHelper: the current domains of
 * the sources live in a public Firebase Realtime Database JSON document.
 * Plain HTTP GET + JSON parse — no Firebase SDK needed.
 */
object DomainHelper {
    private const val URL =
        "https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private val mutex = Mutex()

    @Volatile
    private var domains: Map<String, String> = emptyMap()

    @Volatile
    private var lastLoadTime: Long = 0L

    @Volatile
    private var everLoaded: Boolean = false

    private suspend fun load(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && everLoaded && now - lastLoadTime < CACHE_TTL_MS) return
        mutex.withLock {
            val now2 = System.currentTimeMillis()
            if (!force && everLoaded && now2 - lastLoadTime < CACHE_TTL_MS) return
            try {
                val text = Http.get(URL)
                val obj = JSONObject(text)
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (v.isNotBlank()) map[k] = v.removeSuffix("/")
                }
                domains = map
                everLoaded = true
            } catch (_: Exception) {
                // Keep the old map (or empty) — callers fall back to defaults.
            } finally {
                lastLoadTime = System.currentTimeMillis()
            }
        }
    }

    /** Current domain for [key], or null if unknown. Checks key, key_url, key_domain. */
    suspend fun getDomain(key: String): String? {
        load()
        return domains[key] ?: domains["${key}_url"] ?: domains["${key}_domain"]
    }
}
