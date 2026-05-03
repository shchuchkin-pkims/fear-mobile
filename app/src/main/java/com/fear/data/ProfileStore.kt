package com.fear.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-user identity profile. Two layers:
 *
 *  1. **displayName** — global, "who am I" name. Set once at first launch
 *     (or via the Profile screen later) and reused on every server. Backed
 *     by `profile.displayName` in SharedPreferences `fear_profile`.
 *
 *  2. **registered servers** — set of server hosts where the user has
 *     already claimed `@displayName@host`. Stored as a CSV in
 *     `profile.registeredServers`. The first time the user tries to
 *     connect to a fresh server we prompt to register; subsequent
 *     connections to the same server are silent.
 *
 * For Phase B-1 (no server-side handle protocol yet) the "registration"
 * is purely a local marker: marking the server registered just records the
 * intent. When Phase B-2 ships REGISTER_HANDLE on the server, this same
 * flag will be set only after the server confirms the handle is reserved.
 *
 * Migration: legacy prefs `fear_prefs/connect.name` is back-fed into
 * `profile.displayName` on first read so existing installs don't lose
 * the name they already typed.
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

    /** Mark the user as registered (handle claimed) on `serverHost`. */
    fun markRegistered(serverHost: String) {
        val updated = _state.value.registeredServers + serverHost
        persistRegistered(updated)
        _state.value = _state.value.copy(registeredServers = updated)
    }

    /** Forget registration — used when display name changes (re-register required). */
    fun forgetRegistration(serverHost: String) {
        val updated = _state.value.registeredServers - serverHost
        persistRegistered(updated)
        _state.value = _state.value.copy(registeredServers = updated)
    }

    fun isRegistered(serverHost: String): Boolean =
        _state.value.registeredServers.contains(serverHost)

    /** Compose-friendly handle representation: `@evgenii@fear-project.ru`. */
    fun handleAtServer(serverHost: String): String? {
        val name = _state.value.displayName
        return if (name.isBlank() || !isRegistered(serverHost)) null
        else "@$name@$serverHost"
    }

    private fun persistRegistered(set: Set<String>) {
        prefs.edit().putString(KEY_REGISTERED, set.joinToString(",")).apply()
    }

    private fun load(): ProfileState {
        val explicit = prefs.getString(KEY_NAME, null)
        val migrated = explicit ?: legacyPrefs.getString(LEGACY_NAME_KEY, null)
        // Persist the migration so subsequent reads don't depend on legacy file.
        if (explicit == null && migrated != null) {
            prefs.edit().putString(KEY_NAME, migrated).apply()
        }

        val registeredCsv = prefs.getString(KEY_REGISTERED, null) ?: ""
        val registered = registeredCsv.split(',').filter { it.isNotBlank() }.toSet()

        return ProfileState(
            displayName = migrated.orEmpty(),
            registeredServers = registered,
        )
    }

    companion object {
        private const val PREFS_NAME       = "fear_profile"
        private const val KEY_NAME         = "profile.displayName"
        private const val KEY_REGISTERED   = "profile.registeredServers"
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
    val displayName: String = "",
    val registeredServers: Set<String> = emptySet(),
)
