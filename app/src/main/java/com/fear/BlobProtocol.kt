package com.fear

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * BLOB_PUT / BLOB_GET wire protocol with the F.E.A.R. relay.
 *
 * Same transient-socket pattern as HandleProtocol: open, send one
 * service frame, read one BLOB_RESULT, close. Frame format mirrors the
 * chat wire format with placeholder room/name and zero nonce.
 *
 * The relay is just a dumb store — encryption happens client-side
 * (ContactsCipher). Server-side identity_pk only owns the slot; reads
 * are unauthenticated because the cipher already protects confidentiality.
 */
object BlobProtocol {

    sealed class PutResult {
        object Ok : PutResult()
        data class Invalid(val reason: String) : PutResult()
        data class ServerError(val reason: String) : PutResult()
        data class Network(val cause: Throwable) : PutResult()
    }

    sealed class GetResult {
        data class Found(val cipher: ByteArray) : GetResult()
        object NotFound : GetResult()
        data class ServerError(val reason: String) : GetResult()
        data class Network(val cause: Throwable) : GetResult()
    }

    private val ls: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    suspend fun put(
        host: String, port: Int,
        identityPk: ByteArray,
        sign: (ByteArray) -> ByteArray?,
        type: String, cipher: ByteArray,
        timeoutMs: Int = 10_000,
    ): PutResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            require(identityPk.size == 32)
            val typeBytes = type.toByteArray(Charsets.UTF_8)
            require(typeBytes.size in 1..255)
            require(cipher.size <= 1_048_576) { "cipher too large" }   // 1 MB hard cap

            // sig over (type || cipher) — proves owner of identity_pk
            val signed = ByteArray(typeBytes.size + cipher.size)
            System.arraycopy(typeBytes, 0, signed, 0, typeBytes.size)
            System.arraycopy(cipher,    0, signed, typeBytes.size, cipher.size)
            val sig = sign(signed) ?: return@withContext PutResult.ServerError("sign failed")
            require(sig.size == Sign.ED25519_BYTES)

            // payload: [pk(32)][sig(64)][type_len(1)][type][cipher_len(4)][cipher]
            val payload = ByteArray(32 + 64 + 1 + typeBytes.size + 4 + cipher.size)
            var o = 0
            System.arraycopy(identityPk, 0, payload, o, 32); o += 32
            System.arraycopy(sig,        0, payload, o, 64); o += 64
            payload[o] = typeBytes.size.toByte(); o += 1
            System.arraycopy(typeBytes, 0, payload, o, typeBytes.size); o += typeBytes.size
            Common.writeUInt32(payload, o, cipher.size.toLong()); o += 4
            System.arraycopy(cipher, 0, payload, o, cipher.size)

            val (replyType, body) = roundtrip(host, port,
                Common.MSG_TYPE_BLOB_PUT, payload, timeoutMs)
                ?: return@withContext PutResult.Network(java.io.IOException("no reply"))
            if (replyType != Common.MSG_TYPE_BLOB_RESULT) {
                return@withContext PutResult.ServerError("unexpected reply type=$replyType")
            }
            parsePut(body)
        } catch (e: Exception) {
            PutResult.Network(e)
        }
    }

    suspend fun get(
        host: String, port: Int,
        identityPk: ByteArray, type: String,
        timeoutMs: Int = 10_000,
    ): GetResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            require(identityPk.size == 32)
            val typeBytes = type.toByteArray(Charsets.UTF_8)
            require(typeBytes.size in 1..255)

            // payload: [pk(32)][type_len(1)][type]
            val payload = ByteArray(32 + 1 + typeBytes.size)
            System.arraycopy(identityPk, 0, payload, 0, 32)
            payload[32] = typeBytes.size.toByte()
            System.arraycopy(typeBytes, 0, payload, 33, typeBytes.size)

            val (replyType, body) = roundtrip(host, port,
                Common.MSG_TYPE_BLOB_GET, payload, timeoutMs)
                ?: return@withContext GetResult.Network(java.io.IOException("no reply"))
            if (replyType != Common.MSG_TYPE_BLOB_RESULT) {
                return@withContext GetResult.ServerError("unexpected reply type=$replyType")
            }
            parseGet(body)
        } catch (e: Exception) {
            GetResult.Network(e)
        }
    }

    // ---------- internals ----------

    /**
     * Open a socket, send one frame, read one frame. Returns the (type, payload)
     * tuple of the reply, or null on transport failure.
     */
    private fun roundtrip(
        host: String, port: Int, type: Byte, payload: ByteArray, timeoutMs: Int,
    ): Pair<Byte, ByteArray>? {
        val sock = Socket()
        sock.use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs

            val room  = "__blobs__".toByteArray(Charsets.UTF_8)
            val name  = "__svc__".toByteArray(Charsets.UTF_8)
            val nonce = ByteArray(Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES)

            val frame = buildFrame(room, name, nonce, type, payload)
            s.getOutputStream().write(frame)
            s.getOutputStream().flush()

            val inp = DataInputStream(s.getInputStream())
            val roomLen = readU16(inp);    inp.skipBytes(roomLen)
            val nameLen = readU16(inp);    inp.skipBytes(nameLen)
            val nonceLen = readU16(inp);   inp.skipBytes(nonceLen)
            val replyType = inp.readUnsignedByte().toByte()
            val clen = readU32(inp).toInt()
            val cipher = ByteArray(clen)
            inp.readFully(cipher)
            return replyType to cipher
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

    private fun readU16(d: DataInputStream): Int {
        val a = d.readUnsignedByte()
        val b = d.readUnsignedByte()
        return a or (b shl 8)
    }
    private fun readU32(d: DataInputStream): Long {
        val a = d.readUnsignedByte().toLong()
        val b = d.readUnsignedByte().toLong()
        val c = d.readUnsignedByte().toLong()
        val e = d.readUnsignedByte().toLong()
        return a or (b shl 8) or (c shl 16) or (e shl 24)
    }

    /** payload: [status(1)][reason_len(1)][reason] (no cipher field for PUT) */
    private fun parsePut(payload: ByteArray): PutResult {
        if (payload.isEmpty()) return PutResult.ServerError("empty reply")
        val status = payload[0].toInt() and 0xFF
        val reasonLen = if (payload.size > 1) payload[1].toInt() and 0xFF else 0
        val reason = if (reasonLen > 0 && payload.size >= 2 + reasonLen)
            String(payload, 2, reasonLen, Charsets.UTF_8) else ""
        return when (status) {
            0 -> PutResult.Ok
            2 -> PutResult.Invalid(reason.ifEmpty { "invalid" })
            else -> PutResult.ServerError(reason.ifEmpty { "server error" })
        }
    }

    /** payload: [status(1)][reason_len(1)][reason][cipher_len(4)][cipher] */
    private fun parseGet(payload: ByteArray): GetResult {
        if (payload.isEmpty()) return GetResult.ServerError("empty reply")
        val status = payload[0].toInt() and 0xFF
        val reasonLen = if (payload.size > 1) payload[1].toInt() and 0xFF else 0
        val reason = if (reasonLen > 0 && payload.size >= 2 + reasonLen)
            String(payload, 2, reasonLen, Charsets.UTF_8) else ""
        return when (status) {
            0 -> {
                val o = 2 + reasonLen
                if (payload.size < o + 4) return GetResult.ServerError("short ok header")
                val len = ((payload[o].toInt() and 0xFF)
                        or ((payload[o + 1].toInt() and 0xFF) shl 8)
                        or ((payload[o + 2].toInt() and 0xFF) shl 16)
                        or ((payload[o + 3].toInt() and 0xFF) shl 24))
                if (payload.size < o + 4 + len) return GetResult.ServerError("short ok body")
                GetResult.Found(payload.copyOfRange(o + 4, o + 4 + len))
            }
            1 -> GetResult.NotFound
            else -> GetResult.ServerError(reason.ifEmpty { "server error" })
        }
    }
}
