package com.fear.crypto

/**
 * Отпечаток ключа личности - один на все платформы.
 *
 * Полный: BLAKE2b с 8-байтовым выходом, «40:f6:8f:4a:d2:4e:57:5b».
 * Короткий (для «имя#xxxxxxxx»): первые 4 байта того же хеша - всегда начало
 * полного.
 *
 * Раньше платформы считали по-разному. Ядро на C брало первые 8 байт
 * BLAKE2b-256, а здесь короткий отпечаток был BLAKE2b с 4-байтовым выходом.
 * У BLAKE2b длина выхода входит в параметры, так что это не префиксы друг
 * друга, а разные числа: телефон и ПК показывали для одного ключа разные
 * отпечатки, и сверить их было невозможно - а именно такую сверку README
 * предлагает делать при первом соединении.
 *
 * Ключ закреплён эталоном в FingerprintTest и в test_identity на стороне C:
 * расхождение одной из сторон роняет её собственный тест.
 */
object Fingerprint {
    /** Байт полного отпечатка. */
    const val BYTES = 8

    fun of(pk: ByteArray, hasher: KeyedHash = SodiumKeyedHash): String =
        hasher.blake2b(pk, ByteArray(0), BYTES).joinToString(":") { "%02x".format(it) }

    fun short(pk: ByteArray, hasher: KeyedHash = SodiumKeyedHash): String =
        hasher.blake2b(pk, ByteArray(0), BYTES).copyOf(4).joinToString("") { "%02x".format(it) }

    /**
     * Короткий отпечаток, как его считал Android до 0.6.0: BLAKE2b с
     * 4-байтовым выходом. Только чтобы узнать старые метки в списке
     * доверенных ключей - см. relabelLegacyShortFingerprints.
     */
    fun legacyShort(pk: ByteArray, hasher: KeyedHash = SodiumKeyedHash): String =
        hasher.blake2b(pk, ByteArray(0), 4).joinToString("") { "%02x".format(it) }
}
