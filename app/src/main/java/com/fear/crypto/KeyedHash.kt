package com.fear.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * Keyed BLAKE2b - the only primitive the F.E.A.R. key schedule needs.
 *
 * The seam exists so the derivations below can be unit-tested on a plain
 * JVM: lazysodium-android is an AAR with a native library and cannot load
 * outside a device, while the byte framing (domain string, field order,
 * endianness) is exactly the part that must not drift from the C
 * implementation. Tests inject an independent BLAKE2b and check the same
 * frozen vectors the C tests pin.
 */
fun interface KeyedHash {
    /** BLAKE2b(data, key = key, outLen bytes). */
    fun blake2b(data: ByteArray, key: ByteArray, outLen: Int): ByteArray
}

/** Production hasher: libsodium's crypto_generichash via lazysodium. */
object SodiumKeyedHash : KeyedHash {
    private val ls = LazySodiumAndroid(SodiumAndroid())

    override fun blake2b(data: ByteArray, key: ByteArray, outLen: Int): ByteArray {
        val out = ByteArray(outLen)
        ls.cryptoGenericHash(out, outLen, data, data.size.toLong(), key, key.size)
        return out
    }
}
