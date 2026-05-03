package com.fear.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-facing profile state. Two layers, intentionally distinct:
 *
 *  1. **displayName** — global "what to call me", shown next to my messages.
 *     Set once at first launch (Onboarding) and editable from the Profile
 *     screen. Has no server-side meaning. Backed by `profile.displayName`.
 *
 *  2. **handles** — per-server claimed nicknames in `nickname@server` form.
 *     A handle is reserved by a real REGISTER_HANDLE round-trip with the
 *     relay (see HandleProtocol) and only after the server confirms is the
 *     entry persisted here. Multiple servers, multiple nicknames.
 *
 * On disk: SharedPreferences `fear_profile`. Handles serialised as a CSV of
 * `servernickname` records (the U+0001 separator can't appear in
 * either field — server is hostname/IP, nickname is [A-Za-z0-9._-]).
 *
 * Migration: legacy `connect.name` → `displayName`. Phase B-1's
 * `registeredServers` flag is dropped — having a handle now requires a
 * real server claim, not just a local marker.
 */
class ProfileStore private constructor(
    private val prefs: SharedPreferences,
    private val legacyPrefs: SharedPreferences,
) {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    fun setDisplayName(name: String) {
        val trimmed = name.trim()
        prefs.edit().putString(KEY_NAME, trimmed).apply()
        _state.value = _state.value.copy(displayName = trimmed)
    }

    /** Persist a successfully-registered handle. */
    fun setHandle(serverHost: String, nickname: String) {
        val updated = _state.value.handles + (serverHost to nickname)
        persistHandles(updated)
        _state.value = _state.value.copy(handles = updated)
    }

    /** Drop a handle locally. Server-side reservation is not released. */
    fun removeHandle(serverHost: String) {
        val updated = _state.value.handles - serverHost
        persistHandles(updated)
        _state.value = _state.value.copy(handles = updated)
    }

    fun handleAt(serverHost: String): String? = _state.value.handles[serverHost]

    /** Convenience: human-readable form `nickname@server` (no leading @). */
    fun handleString(serverHost: String): String? {
        val nick = handleAt(serverHost) ?: return null
        return "$nick@$serverHost"
    }

    private fun persistHandles(map: Map<String, String>) {
        val csv = map.entries.joinToString(",") { (s, n) -> "$s$n" }
        prefs.edit().putString(KEY_HANDLES, csv).apply()
    }

    private fun load(): ProfileState {
        val explicit = prefs.getString(KEY_NAME, null)
        val migrated = explicit ?: legacyPrefs.getString(LEGACY_NAME_KEY, null)
        if (explicit == null && migrated != null) {
            prefs.edit().putString(KEY_NAME, migrated).apply()
        }

        val csv = prefs.getString(KEY_HANDLES, null) ?: ""
        val handles = csv.split(',').mapNotNull { rec ->
            if (rec.isBlank()) null
            else rec.split('', limit = 2).let { p ->
                if (p.size == 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) p[0] to p[1] else null
            }
        }.toMap()

        return ProfileState(
            displayName = migrated.orEmpty(),
            handles     = handles,
        )
    }

    companion object {
        private const val PREFS_NAME       = "fear_profile"
        private const val KEY_NAME         = "profile.displayName"
        private const val KEY_HANDLES      = "profile.handles"      // servernick,servernick
        private const val LEGACY_PREFS     = "fear_prefs"
        private const val LEGACY_NAME_KEY  = "connect.name"

        @Volatile private var INSTANCE: ProfileStore? = null

        fun get(ctx: Context): ProfileStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProfileStore(
                    ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                    ctx.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE),
                ).also { INSTANCE = it }
            }
        }
    }
}

data class ProfileState(
    /** Global "what to call me" — shown next to messages. Not bound to a server. */
    val displayName: String = "",
    /** Server hostname → claimed nickname. Populated after server confirms REGISTER_HANDLE. */
    val handles: Map<String, String> = emptyMap(),
)
