package com.fear.data

import android.content.Context
import android.util.Log
import com.fear.BlobProtocol
import com.fear.IdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Local contact list + encrypted sync with the relay (Phase B-3).
 *
 * Two halves:
 *   • A thin facade over ContactDao for the UI/viewmodel.
 *   • Background sync — debounced upload of the encrypted blob after any
 *     local mutation, eager download on `pullFromServer()` (called when a
 *     new device joins or when the user explicitly refreshes).
 *
 * The blob is end-to-end encrypted under K_contacts derived from
 * identity_sk (see ContactsCipher), so the relay sees only opaque bytes.
 */
class ContactsRepository private constructor(private val ctx: Context) {

    private val dao = AppDatabase.get(ctx).contactDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPush: Job? = null

    fun observeAll(): Flow<List<ContactEntity>> = dao.observeAll()
    suspend fun all(): List<ContactEntity> = dao.all()

    /** Add or update one contact. Schedules a push to the configured server. */
    suspend fun upsert(contact: ContactEntity, server: ServerEndpoint?) {
        dao.upsert(contact)
        schedulePush(server)
    }

    suspend fun delete(pkB64: String, server: ServerEndpoint?) {
        dao.deleteByPk(pkB64)
        schedulePush(server)
    }

    /**
     * Download the user's blob from `server`, decrypt, replace the local
     * contact list. Returns the number of contacts after the merge, or
     * a negative value on error.
     *
     *   -1  no identity yet
     *   -2  network / not found (local list left untouched)
     *   -3  decrypt failed (tampered or wrong identity)
     */
    suspend fun pullFromServer(server: ServerEndpoint): Int {
        val im = IdentityManager(ctx)
        val pk = im.getPublicKey() ?: return -1
        val key = im.deriveSymmetricKey(KDF_CTX) ?: return -1

        val rc = BlobProtocol.get(server.host, server.port, pk,
            sign = { msg -> im.sign(msg) },
            type = BLOB_TYPE)
        return when (rc) {
            is BlobProtocol.GetResult.Found -> {
                try {
                    val list = ContactsCipher.decrypt(rc.cipher, key)
                    dao.replaceAll(list)
                    key.fill(0)
                    list.size
                } catch (e: Exception) {
                    key.fill(0)
                    Log.w(TAG, "decrypt failed: ${e.message}")
                    -3
                }
            }
            BlobProtocol.GetResult.NotFound -> {
                key.fill(0)
                0   // brand-new identity on this server, that's fine
            }
            is BlobProtocol.GetResult.ServerError -> {
                key.fill(0); Log.w(TAG, "pull: server=${rc.reason}"); -2
            }
            is BlobProtocol.GetResult.Network -> {
                key.fill(0); Log.w(TAG, "pull: net=${rc.cause.message}"); -2
            }
        }
    }

    /**
     * Schedule a debounced push of the current contact list to `server`.
     * Multiple calls within the debounce window collapse into one round-trip.
     * If `server` is null (offline / not connected), we silently skip — the
     * push will happen at the next call when there *is* a server.
     */
    fun schedulePush(server: ServerEndpoint?) {
        server ?: return
        pendingPush?.cancel()
        pendingPush = scope.launch {
            delay(DEBOUNCE_MS)
            pushNow(server)
        }
    }

    private suspend fun pushNow(server: ServerEndpoint) {
        val im = IdentityManager(ctx)
        val pk = im.getPublicKey() ?: return
        val key = im.deriveSymmetricKey(KDF_CTX) ?: return
        val cipher = try {
            ContactsCipher.encrypt(dao.all(), key)
        } catch (e: Exception) {
            key.fill(0); Log.e(TAG, "encrypt failed", e); return
        }
        key.fill(0)

        val rc = BlobProtocol.put(
            server.host, server.port, pk,
            sign = { msg -> im.sign(msg) },
            type = BLOB_TYPE,
            cipher = cipher,
        )
        when (rc) {
            BlobProtocol.PutResult.Ok ->
                Log.i(TAG, "contacts pushed (${cipher.size} bytes) to ${server.host}")
            is BlobProtocol.PutResult.Invalid ->
                Log.w(TAG, "contacts push invalid: ${rc.reason}")
            is BlobProtocol.PutResult.ServerError ->
                Log.w(TAG, "contacts push server: ${rc.reason}")
            is BlobProtocol.PutResult.Network ->
                Log.w(TAG, "contacts push net: ${rc.cause.message}")
        }
    }

    data class ServerEndpoint(val host: String, val port: Int)

    companion object {
        private const val TAG = "FearContacts"
        const val BLOB_TYPE  = "contacts.v1"
        const val KDF_CTX    = "fear.contacts.v1"
        const val DEBOUNCE_MS = 5_000L

        @Volatile private var INSTANCE: ContactsRepository? = null
        fun get(ctx: Context): ContactsRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ContactsRepository(ctx.applicationContext).also { INSTANCE = it }
        }
    }
}
