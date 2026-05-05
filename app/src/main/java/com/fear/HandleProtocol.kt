package com.fear

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Out-of-band protocol with the F.E.A.R. relay for the handle registry
 * (Phase B-2). Each call opens a fresh short-lived TCP socket, sends one
 * service frame, reads the HANDLE_RESULT reply, then closes.
 *
 * The relay's read loop short-circuits these frames before any room
 * registration, so the same TCP port is used for both chat and handle
 * RPC — there is no second listener.
 *
 * Frame format mirrors the chat wire format exactly (see FearClient
 * .buildFrame), with the cipher field carrying the raw payload because
 * service messages travel un-encrypted (zero nonce).
 *
 * Usage from the UI thread is fine — every method is a suspend wrapper
 * that switches to Dispatchers.IO internally.
 */
object HandleProtocol {

    sealed class Result {
        object Ok : Result()
        data class Conflict(val reason: String) : Result()
        data class Invalid(val reason: String) : Result()
        data class ServerError(val reason: String) : Result()
        data class Network(val cause: Throwable) : Result()
    }

    /** Lookup result. `pk == null` means handle not found. */
    sealed class LookupResult {
        data class Found(val pk: ByteArray) : LookupResult()
        object NotFound : LookupResult()
        data class ServerError(val reason: String) : LookupResult()
        data class Network(val cause: Throwable) : LookupResult()
    }

    private val ls: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    /**
     * Register `handle` on the relay at `host:port`.
     *
     * Signing is delegated through `sign` so the caller's identity_sk never
     * has to leave its EncryptedFile-backed home.
     */
    suspend fun registerHandle(
        host: String,
        port: Int,
        handle: String,
        identityPk: ByteArray,
        sign: (ByteArray) -> ByteArray?,
        timeoutMs: Int = 8000,
    ): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        android.util.Log.i("HandleProtocol", "registerHandle host=$host port=$port handle=$handle")
        try {
            require(identityPk.size == 32) { "identity_pk must be 32 bytes" }
            val handleBytes = handle.toByteArray(Charsets.UTF_8)
            require(handleBytes.size in 1..255) { "handle length out of range" }

            val sig = sign(handleBytes)
                ?: return@withContext Result.ServerError("client signature failed")
            require(sig.size == Sign.ED25519_BYTES) { "signature wrong size" }

            // REGISTER_HANDLE payload: [pk(32)][sig(64)][handle_len(1)][handle]
            val payload = ByteArray(32 + 64 + 1 + handleBytes.size).apply {
                System.arraycopy(identityPk, 0, this, 0, 32)
                System.arraycopy(sig,        0, this, 32, 64)
                this[96] = handleBytes.size.toByte()
                System.arraycopy(handleBytes, 0, this, 97, handleBytes.size)
            }
            val reply = roundtrip(host, port,
                                  Common.MSG_TYPE_REGISTER_HANDLE, payload, timeoutMs)
                ?: return@withContext Result.Network(java.io.IOException("no reply"))
            parseRegisterReply(reply)
        } catch (e: Exception) {
            android.util.Log.w("HandleProtocol", "registerHandle network: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.Network(e)
        }
    }

    suspend fun lookupHandle(
        host: String,
        port: Int,
        handle: String,
        timeoutMs: Int = 5000,
    ): LookupResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val handleBytes = handle.toByteArray(Charsets.UTF_8)
            require(handleBytes.size in 1..255) { "handle length out of range" }
            val payload = ByteArray(1 + handleBytes.size).apply {
                this[0] = handleBytes.size.toByte()
                System.arraycopy(handleBytes, 0, this, 1, handleBytes.size)
            }
            val reply = roundtrip(host, port,
                                  Common.MSG_TYPE_LOOKUP_HANDLE, payload, timeoutMs)
                ?: return@withContext LookupResult.Network(java.io.IOException("no reply"))
            parseLookupReply(reply)
        } catch (e: Exception) {
            LookupResult.Network(e)
        }
    }

    // ----------------- internals -----------------

    /**
     * Open a socket, send one frame, read one frame, return the cipher field
     * of the reply (= the HANDLE_RESULT payload). Returns null on transport
     * failure.
     */
    private fun roundtrip(
        host: String, port: Int, type: Byte, payload: ByteArray, timeoutMs: Int,
    ): ByteArray? {
        val sock = Socket()
        sock.use { s ->
            s.connect(InetSocketAddress(host.trim(), port), timeoutMs)
            s.soTimeout = timeoutMs

            // The relay needs a non-empty room/name in the frame for parsing
            // (handle commands are detected by `type` after the room/name
            // fields). Use placeholders so the relay routing/conflict checks
            // never fire on this transient socket.
            val room = "__handles__".toByteArray(Charsets.UTF_8)
            val name = "__svc__".toByteArray(Charsets.UTF_8)
            val nonce = ByteArray(Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES)  // zero-nonce service msg

            val frame = buildFrame(room, name, nonce, type, payload)
            s.getOutputStream().write(frame)
            s.getOutputStream().flush()

            return readReplyFrame(s)
        }
    }

    private fun buildFrame(
        room: ByteArray, name: ByteArray, nonce: ByteArray,
        type: Byte, payload: ByteArray,
    ): ByteArray {
        val sz = 2 + room.size + 2 + name.size + 2 + nonce.size + 1 + 4 + payload.size
        val out = ByteArray(sz)
        var o = 0
        Common.writeUInt16(out, o, room.size); o += 2
        System.arraycopy(room, 0, out, o, room.size); o += room.size
        Common.writeUInt16(out, o, name.size); o += 2
        System.arraycopy(name, 0, out, o, name.size); o += name.size
        Common.writeUInt16(out, o, nonce.size); o += 2
        System.arraycopy(nonce, 0, out, o, nonce.size); o += nonce.size
        out[o] = type; o += 1
        Common.writeUInt32(out, o, payload.size.toLong()); o += 4
        System.arraycopy(payload, 0, out, o, payload.size)
        return out
    }

    /** Read one chat frame and return its cipher payload (or null on EOF). */
    private fun readReplyFrame(s: Socket): ByteArray? {
        val inp = DataInputStream(s.getInputStream())
        val roomLen = readU16(inp);    inp.skipBytes(roomLen)
        val nameLen = readU16(inp);    inp.skipBytes(nameLen)
        val nonceLen = readU16(inp);   inp.skipBytes(nonceLen)
        val type = inp.readUnsignedByte().toByte()
        val clen = readU32(inp).toInt()
        val cipher = ByteArray(clen)
        inp.readFully(cipher)
        if (type != Common.MSG_TYPE_HANDLE_RESULT) {
            Log.w("HandleProtocol", "unexpected reply type=$type clen=$clen")
            return null
        }
        return cipher
    }

    private fun readU16(d: DataInputStream): Int {
        val a = d.readUnsignedByte()
        val b = d.readUnsignedByte()
        return a or (b shl 8)         // little-endian
    }
    private fun readU32(d: DataInputStream): Long {
        val a = d.readUnsignedByte().toLong()
        val b = d.readUnsignedByte().toLong()
        val c = d.readUnsignedByte().toLong()
        val e = d.readUnsignedByte().toLong()
        return a or (b shl 8) or (c shl 16) or (e shl 24)
    }

    private fun parseRegisterReply(payload: ByteArray): Result {
        if (payload.isEmpty()) return Result.ServerError("empty reply")
        val status = payload[0].toInt() and 0xFF
        val reasonLen = (if (payload.size > 1) payload[1].toInt() and 0xFF else 0)
        val reason = if (reasonLen > 0 && payload.size >= 2 + reasonLen)
            String(payload, 2, reasonLen, Charsets.UTF_8) else ""
        return when (status) {
            0 -> Result.Ok
            1 -> Result.Conflict(reason.ifEmpty { "handle taken" })
            2 -> Result.Invalid(reason.ifEmpty { "invalid handle" })
            else -> Result.ServerError(reason.ifEmpty { "server error" })
        }
    }

    private fun parseLookupReply(payload: ByteArray): LookupResult {
        if (payload.isEmpty()) return LookupResult.ServerError("empty reply")
        val status = payload[0].toInt() and 0xFF
        val reasonLen = if (payload.size > 1) payload[1].toInt() and 0xFF else 0
        val pkOffset = 2 + reasonLen
        return when (status) {
            0 -> {
                if (payload.size < pkOffset + 32) LookupResult.ServerError("short ok")
                else LookupResult.Found(payload.copyOfRange(pkOffset, pkOffset + 32))
            }
            1 -> LookupResult.NotFound
            else -> {
                val reason = if (reasonLen > 0) String(payload, 2, reasonLen, Charsets.UTF_8)
                             else "server error"
                LookupResult.ServerError(reason)
            }
        }
    }
}
