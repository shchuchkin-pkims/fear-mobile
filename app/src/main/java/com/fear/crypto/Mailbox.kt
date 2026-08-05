package com.fear.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * Офлайн-ящик: письмо тому, кого сейчас нет в комнате.
 *
 * Зеркало того, что делает консольный клиент, и формат обязан совпадать
 * байт в байт - иначе письмо с телефона не откроется на ПК и наоборот.
 *
 * Адрес слепой: BLAKE2b под ключом пары K_pm. Вычислить его может лишь
 * тот, у кого этот секрет есть, то есть двое собеседников; ретранслятор
 * видит непрозрачную метку и не знает, ни кому письмо, ни от кого. Знание
 * адреса и есть право забрать почту - ключа пары у сервера нет и быть не
 * должно, а значит и подпись под ним он не проверит.
 *
 * Имя отправителя едет внутри запечатанного письма, а не рядом с ним:
 * рядом его увидел бы сервер, а в связанных данных получателю пришлось бы
 * знать имя заранее, чтобы письмо открыть. Поэтому в связанных данных
 * стоит постоянное «inbox».
 */
object Mailbox {

    const val ADDR_BYTES = 32

    /** То же, что IDENTITY_INBOX_ADDR_BYTES и контекст в identity.c. */
    private const val ADDR_CTX = "fear.inbox.v1"

    /** Имя в связанных данных - одинаковое у обеих сторон. */
    private const val AD_NAME = "inbox"

    /** Слепой адрес ящика пары. */
    fun address(kPm: ByteArray, hasher: KeyedHash = SodiumKeyedHash): ByteArray =
        hasher.blake2b(ADDR_CTX.toByteArray(Charsets.US_ASCII), kPm, ADDR_BYTES)

    /**
     * Запечатать письмо: [1 байт длины имени][имя][текст].
     *
     * Возвращает то, что кладётся в кадр после адреса: nonce и печать.
     * Nonce едет с письмом, потому что открывать его будут не сейчас и не
     * на этом соединении.
     */
    fun seal(
        room: String,
        kPm: ByteArray,
        senderName: String,
        text: ByteArray,
        nonce: ByteArray,
    ): ByteArray? {
        val name = senderName.toByteArray(Charsets.UTF_8)
        if (name.size > 255) return null

        val plain = ByteArray(1 + name.size + text.size)
        plain[0] = name.size.toByte()
        name.copyInto(plain, 1)
        text.copyInto(plain, 1 + name.size)

        val sealed = ChatFrame.seal(
            ChatFrame.RoomKey(ChatFrame.KEY_VERSION, kPm),
            room.toByteArray(Charsets.UTF_8),
            AD_NAME.toByteArray(Charsets.US_ASCII),
            plain, nonce,
        ) ?: return null
        plain.fill(0)

        return nonce + sealed
    }

    /** Отправитель и текст письма, или null если оно не наше. */
    data class Letter(val sender: String, val text: String)

    fun open(room: String, kPm: ByteArray, body: ByteArray): Letter? {
        val nonceLen = com.fear.Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES
        if (body.size <= nonceLen) return null

        val nonce = body.copyOfRange(0, nonceLen)
        val sealed = body.copyOfRange(nonceLen, body.size)

        val plain = ChatFrame.open(
            listOf(ChatFrame.RoomKey(ChatFrame.KEY_VERSION, kPm)),
            room.toByteArray(Charsets.UTF_8),
            AD_NAME.toByteArray(Charsets.US_ASCII),
            sealed, nonce,
        ) ?: return null

        if (plain.isEmpty()) return null
        val nameLen = plain[0].toInt() and 0xFF
        if (1 + nameLen > plain.size) return null

        val sender = String(plain, 1, nameLen, Charsets.UTF_8)
        val text = String(plain, 1 + nameLen, plain.size - 1 - nameLen, Charsets.UTF_8)
        plain.fill(0)
        return Letter(sender, text)
    }
}
